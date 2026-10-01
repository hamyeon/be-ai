# AWS 시연 환경 배포 런북

중간발표(및 이후 최종발표)용 공개 HTTPS 시연 환경을 AWS에 올리는 절차. `docs/deployment-config.md`
(관리 엔드포인트·AI 호출 로그 등 일반 배포 환경변수)와 함께 본다.

**이 문서 작성 시점 기준: 로컬 AWS 자격증명이 만료돼 있어 AWS 자원 생성·실제 배포·URL 검증은
수행하지 못했다.** 아래는 파일 구현과 로컬에서 가능한 검증까지만 반영한 계획이다. "배포 후
확인 필요"로 표시된 항목은 실제로 자원을 만들고 배포한 뒤에만 확인할 수 있다.

## 구성 요약

- **API EC2 1대**: 웹 API 전체 + 도메인 스케줄러(경매 시작/종료, 결제·차순위 만료, 구매에이전트
  탐색) + MySQL·Redis 컨테이너 + Caddy(공개 HTTPS 진입점).
- **Worker EC2 1대**: Redis Streams 소비(XREADGROUP)·PEL 회수(XAUTOCLAIM)·Vision 분석·DB 저장·
  XACK만. 웹 트래픽 없음, 공인 인터넷에 미노출.
- **HTTPS**: 도메인 구매/Elastic IP 없이, EC2 자동 할당 공인 IP + `<ip>.sslip.io` + Caddy 자동
  Let's Encrypt.
- **MySQL/Redis**: API EC2 위 컨테이너. 공인 인터넷에 미노출(사설 IP에만 바인딩 + 보안그룹).
  기존 팀 공유 dev RDS(us-east-1)는 사용하지 않는다 - 새 시연 데이터로 시작한다.
- **배포**: GitHub Actions → OIDC(AssumeRole, 장기 키 없음) → ECR → SSM RunCommand로 API/Worker에
  동일 커밋 SHA 배포 → 각자 `/actuator/health`로 준비 상태 확인 → 실패 시 이전 정상 이미지로 롤백.

---

## 1. 새로 생성할 AWS 자원과 예상 비용

리전: `ap-northeast-2`(서울) - 팀 공유 dev RDS(us-east-1)와 무관하게, 이전 인프라 실험 계정/
관례와 맞춘다. 이전 실험 자원(VPC/ECR/SQS 등)은 전부 삭제된 상태라고 가정하고 전부 새로 만든다.

| 자원 | 용도 | 대략 비용(24/7 기준) |
|---|---|---|
| EC2 API `t3.medium`(4GiB) | API + MySQL + Redis + Caddy | ~$0.052/hr ≈ $37/월 |
| EC2 Worker `t3.small`(2GiB) | Worker(Vision 분석) | ~$0.026/hr ≈ $19/월 |
| 공인 IPv4 자동할당 × 2 | 실행 중에만 과금(2024-02~) | ~$7.3/월 |
| EBS gp3 (루트 + `mysql-data` 볼륨) | 스토리지 | ~$3/월 |
| ECR 리포지토리 | 이미지 저장 | <$1/월 |
| VPC/서브넷/보안그룹/IAM 역할/OIDC provider | 자체는 무료 | $0 |
| **Elastic IP·ALB·RDS·NAT Gateway** | **사용 안 함** | $0 |
| **합계(상시)** | | **~$67/월** |

**저비용 대안**(API `t3.small` + Worker `t3.micro`, ~$40/월)은 이 문서의 컨테이너 메모리 상한을
전부 건 상태에서 실측(`docker stats`)한 뒤에만 채택한다 - 측정 전에 안정적이라고 단정하지 않는다.

발표 기간(1주)만 쓰고 평소엔 EC2를 **중지(Stop)** 하면 컴퓨트+공인 IPv4 과금이 멈춘다(§7).
실사용 비용은 위 월액의 극히 일부로 예상된다.

### 메모리 산정 근거

기존 `Dockerfile`은 `java -jar`만 실행해 **힙 상한이 없었다** - JVM 기본
`MaxRAMPercentage=25%`는 컨테이너가 아니라 **호스트 전체 RAM**을 기준으로 계산된다. Redis도
`maxmemory` 미설정, MySQL도 `innodb_buffer_pool_size` 기본값(~128MB)이었다. 이 상태로는 인스턴스
크기별 안정성을 가늠할 수 없어, `docker-compose.aws-api.yml`/`docker-compose.aws-worker.yml`에
`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60` + 각 서비스 `mem_limit`을 명시적으로 걸었다.

t3.medium(4096MiB) 기준 배분: api 2048m + mysql 768m + redis 320m + caddy 96m = 3232m,
OS/Docker 데몬 몫 ~864m 남김. t3.small(2048MiB) 기준 worker: 1536m, OS 몫 ~512m 남김.
**실제 부하 시 여유가 충분한지는 배포 후 `docker stats`로 확인이 필요하다(배포 후 확인 필요).**

---

## 2. IAM 인스턴스 역할 — 권한 표

정적 `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`는 배포 환경 어디에도 넣지 않는다. S3는 EC2
인스턴스 역할로만 인증한다(`S3Config`가 access-key/secret-key가 비어있으면 자동으로
`DefaultCredentialsProvider`로 전환 - 로컬 개발은 기존처럼 정적 키를 채워 쓸 수 있다).

| 권한 | API 역할 | Worker 역할 | 근거 |
|---|---|---|---|
| `s3:PutObject` on `<bucket>/*` | ✅ | ❌ | 업로드는 API만(`S3UploaderService`) |
| `s3:GetObject` on `<bucket>/*` | ✅(표시용 presign) | ✅(분석용 presign) | §4 |
| `ecr:GetAuthorizationToken` | ✅ | ✅ | 각 인스턴스가 자기 이미지를 직접 `docker pull` |
| `ecr:BatchGetImage`, `ecr:GetDownloadUrlForLayer`, `ecr:BatchCheckLayerAvailability` | ✅ | ✅ | 〃 |
| `ssm:GetParameter(s)` (+`kms:Decrypt`, SecureString) on `/vintic/demo/*` | ✅(자기 몫만) | ✅(자기 몫만) | env 부트스트랩(§3) |
| `AmazonSSMManagedInstanceCore`(관리형 정책) | ✅ | ✅ | SSM Session Manager로 관리(22번 포트 미개방) |

**Worker도 S3 GetObject가 필요한 이유**: presigned URL의 접근 권한은 서명 시점이 아니라 **실제로
GET하는 시점의 서명 주체 권한**으로 평가된다. Worker가 Vision 호출 직전 presign한 URL을
OpenAI/Claude가 실제로 fetch하는 시점에, Worker의 IAM 역할에 GetObject 권한이 없으면 403이 난다.

### 컨테이너 안에서 IAM 역할 자격증명 조회 — IMDSv2 주의

Docker 컨테이너는 기본 bridge 네트워크라 인스턴스 메타데이터(169.254.169.254) 요청이 호스트→
컨테이너로 **홉이 하나 늘어난다**. IMDSv2는 홉 제한(`HttpPutResponseHopLimit`) 기본값이 1이라,
컨테이너 안에서는 기본값으로 토큰 발급(PUT)이 실패할 수 있다. **EC2 생성 시 반드시**
`--metadata-options "HttpTokens=required,HttpPutResponseHopLimit=2"` 를 지정한다(hop-limit 2 이상).

검증법(배포 후 확인 필요): 컨테이너 안에서
```bash
curl -s -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600"
```
토큰이 발급되는지 확인 → 이어서 API의 `POST /api/products/analyze`(S3 업로드)와 Worker의 Vision
호출(presign)이 실제로 되는지 확인한다.

---

## 3. 사용자가 설정할 비밀/변수

값 자체는 이 문서에 적지 않는다.

### GitHub → repo Settings → Secrets and variables → Actions

| 종류 | 이름 | 설명 |
|---|---|---|
| Secret | `AWS_ROLE_ARN` | OIDC로 GitHub Actions가 assume할 배포 역할 ARN |
| Variable | `AWS_ACCOUNT_ID` | AWS 계정 ID |
| Variable | `AWS_REGION` | `ap-northeast-2` |
| Variable | `ECR_REPOSITORY` | ECR 리포지토리 이름 |
| Variable | `PROJECT_TAG` | EC2 태그 `Project` 값(인스턴스 검색 기준) |
| Variable | `ENVIRONMENT_TAG` | EC2 태그 `Environment` 값 |
| Variable | `LAST_GOOD_PARAMETER` | 예: `/vintic/demo/deploy/last-known-good` |
| Variable | `AUTO_DEPLOY` | 초기 미설정(또는 `false`). 첫 배포 검증 후 `true`로 전환(§5) |

CI 테스트용 `OPENAI_API_KEY`/`JWT_SECRET`은 워크플로 안에 플레이스홀더 값으로 이미 넣어뒀다 -
실제 키가 아니므로 GitHub Secret으로 등록할 필요가 없다.

### AWS SSM Parameter Store (SecureString 권장) → EC2의 `/opt/autique/api.env` / `worker.env`로 부트스트랩

| SSM 파라미터(예시 경로) | 컨테이너 env로 매핑 | 비고 |
|---|---|---|
| `/vintic/demo/db/username` | `SPRING_DATASOURCE_USERNAME`, `MYSQL_USER` | API/Worker 공용(동일 값) |
| `/vintic/demo/db/password` | `SPRING_DATASOURCE_PASSWORD`, `MYSQL_PASSWORD` | API/Worker 공용(동일 값) |
| `/vintic/demo/db/root-password` | `MYSQL_ROOT_PASSWORD` | mysql 컨테이너 전용(앱은 쓰지 않음) |
| `/vintic/demo/openai-api-key` | `OPENAI_API_KEY` | |
| `/vintic/demo/jwt-secret` | `JWT_SECRET` | 32자 이상, 신규 생성(과거 값 재사용 금지) |
| `/vintic/demo/s3-bucket` | `CLOUD_AWS_S3_BUCKET` | |
| `/vintic/demo/cors-allowed-origins` | `CORS_ALLOWED_ORIGINS` | 프론트 오리진(§6) |
| (Worker만) `/vintic/demo/redis-host` | `SPRING_DATA_REDIS_HOST` | API의 **사설 IP** |
| (배포 스크립트) `/vintic/demo/deploy/last-known-good` | - | `scripts/aws/deploy.sh`가 직접 갱신 |

**`SPRING_DATASOURCE_URL`은 SSM에 두지 않는다.** API와 Worker가 접속하는 MySQL 호스트가 서로
달라(API=Compose 서비스 이름 `mysql`, Worker=API EC2의 **사설 IP**) 파라미터 하나로 두 값을
동시에 줄 수 없는데, 접속 주소 자체는 계정/비밀번호와 달리 비밀값이 아니다. 새 SSM 파라미터
경로를 추가하지 않고(IAM 정책이 허용하는 경로를 다시 맞출 필요가 없도록), 아래 "EC2에 직접
두는 값"에서 각 EC2의 env 파일에 사람이 직접 적는다. 계정/비밀번호는 계속 위 표의 공용 SSM
파라미터에서 가져온다. 같은 이유로 API의 `SPRING_DATA_REDIS_HOST`도 SSM을 거치지 않는다 -
Compose 네트워크 안의 고정된 서비스 이름(`redis`)이라 `docker-compose.aws-api.yml`의
`api.environment`에 직접 선언돼 있다(Worker는 API EC2가 생성돼야 알 수 있는 사설 IP라서 위
표의 SSM 파라미터를 그대로 쓴다 - API와 달리 고정값이 아니다).

시연 중 벡터 백필 비활성화는 코드 기본값(`application-api.yml`)이 이미 꺼져 있다 - 켜려면 API
컨테이너 env에 `RECOMMENDATION_VECTOR_BACKFILL=true`를 추가하고 재배포한다(§8 참고, 배포 후 확인 필요).

### EC2에 직접 두는 값

- `/opt/autique/.env`: `API_PRIVATE_IP`(API EC2 자신의 사설 IP, mysql/redis 포트 바인딩용),
  `SITE_ADDRESS`(`<공인IP-하이픈>.sslip.io`), `IMAGE_URI`(배포 스크립트가 매 배포마다 갱신).
- `/opt/autique/api.env`: `SPRING_DATASOURCE_USERNAME`·`MYSQL_USER`(위 `/vintic/demo/db/username`과
  **동일한 값**), `SPRING_DATASOURCE_PASSWORD`·`MYSQL_PASSWORD`(위 `/vintic/demo/db/password`와
  **동일한 값** - `mysql` 서비스도 같은 `api.env`를 `env_file`로 읽으므로, API가 접속하는 계정과
  MySQL 컨테이너가 실제로 만드는 계정이 다르면 API가 기동 직후부터 인증 실패로 죽는다), 그 외
  `MYSQL_ROOT_PASSWORD`, `OPENAI_API_KEY`, `JWT_SECRET`, `CLOUD_AWS_S3_BUCKET`,
  `CORS_ALLOWED_ORIGINS`(위 SSM 파라미터를 그대로 옮겨 적음) + 리터럴
  `SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/autique`(SSM이 아님, 위 설명 참고).
- `/opt/autique/worker.env`: `SPRING_DATASOURCE_USERNAME`·`SPRING_DATASOURCE_PASSWORD`(API와
  동일한 SSM 파라미터에서 옮겨 적은 **같은 계정** - Worker도 API EC2 위 같은 MySQL을 쓴다),
  `OPENAI_API_KEY`, `JWT_SECRET`(API와 동일 - `application.yml`의 `jwt.secret: ${JWT_SECRET}`은
  기본값이 없어 `dev` profile을 함께 쓰는 Worker도 이 값 없이는 기동 자체가 실패한다),
  `CLOUD_AWS_S3_BUCKET`(위 SSM 파라미터를 그대로 옮겨 적음) + 리터럴
  `SPRING_DATASOURCE_URL=jdbc:mysql://<API_PRIVATE_IP>:3306/autique`,
  `SPRING_DATA_REDIS_HOST=<API_PRIVATE_IP>`(둘 다 API EC2의 실제 사설 IP로 치환, SSM이 아님 -
  API EC2가 만들어진 뒤에만 알 수 있는 값이라 애초에 SSM에 둘 이유가 없다. Redis host만은
  예외적으로 위 SSM 표의 `/vintic/demo/redis-host`에서 옮겨 적어도 된다 - API 생성 직후
  한 번만 기록해 두면 재사용 가능하기 때문).
- 두 파일 모두 mode 600, git 비추적. 최초 1회는 사람이 SSM에서 공용 값을 읽고 나머지(URL)는
  직접 적어 수동으로 만들거나, 부트스트랩 스크립트로 만든다(이 스크립트는 이번 범위에 포함하지
  않았다 - 배포 후 필요 시 추가).

---

## 4. S3 이미지 접근 — presign 범위와 남겨둔 정책 질문

`S3UploaderService`는 원본(표시용)과 `analysis/` 프리픽스 사본(Vision 분석용)을 같은 버킷에
**공개 형식 URL**로 올린다(`https://<bucket>.s3.<region>.amazonaws.com/<key>`). 버킷을
public-read로 바로 열지 말라는 요구에 따라, 버킷은 퍼블릭 액세스 차단을 유지하고 **필요한
순간에만 presigned GET URL**을 만들어 넘긴다(`S3UrlPresigner`, `S3Config`의 `S3Presigner` 빈):

- **분석 경로**: `AnalysisTaskConsumer.processVisionAndFinish`가 Vision 호출 **직전**에
  `analysis/` 사본 URL을 presign(TTL 1시간). 업로드 시점에 미리 서명해 두지 않는 이유: PEL
  회수로 한참 뒤 재처리될 수 있어 그때는 서명이 만료돼 있을 수 있다.
- **표시 경로(분석 세션 폴링)**: `ProductAnalyzeService.getStatus`가 원본 URL을 presign(TTL
  6시간) - `POST /api/products/analyze`가 인증 필수로 바뀌고(§5) 세션에 소유자 검증이 생기면서
  (`ProductAnalysisSession.userId`), 그 밑의 이미지 접근도 로그인한 소유자로만 좁혔다.

**남겨둔 정책 질문(구현하지 않음)**: 상품 등록 후 `ProductResponse`/`AuctionDetailResponse` 등
공개 마켓플레이스 조회 응답(전부 `permitAll`, 누구나 익명으로 호출 가능)이 반환하는 이미지
URL은 이번 범위에서 presign하지 않았다 - "업로드와 AI 분석의 이미지 접근 경로"로 범위를
좁혔고, 이미 익명 공개인 엔드포인트에 presign을 씌워도 접근 범위가 실제로 좁아지지 않는다(URL을
받은 사람은 누구나 쓸 수 있다는 점은 presign 여부와 무관).
문제는 **같은 S3 키가 두 국면(비공개 분석 세션 ↔ 공개 상품 등록 후)에서 재사용된다는 것**이다 -
버킷을 전면 비공개로 유지하려면 마켓플레이스 응답도 결국 presign해야 하는데(7개 이상의 DTO를
touch), 혹은 "게시(등록)" 시점에 객체를 공개 프리픽스로 복사/이동하는 실제 기능을 새로 만들어야
한다. 둘 다 이번 1주 배포 범위보다 크다. **당장은 버킷 전체를 비공개로 두면 등록된 상품 사진이
마켓플레이스에서 깨진 이미지로 보인다** - 시연 전 아래 중 하나를 정해야 한다(배포 후 확인 필요/
사용자 결정 필요):
  1. 등록된 상품 이미지가 쓰는 프리픽스만 버킷 정책으로 공개 read 허용(단, 원본 키가 비공개
     분석 세션 단계와 같은 키라 완벽한 격리는 아니다 - 세션 ID를 아는 사람만 접근 가능한 수준의
     보호로 충분하다면 채택 가능).
  2. 마켓플레이스 응답도 presign(작업량 큼, 이번엔 보류).

---

## 5. 첫 배포 실행 순서

전제: 아래 순서는 AWS 자격증명이 복구된 뒤 사용자가 직접 실행한다. **이번 세션에서는
git add/commit/push, PR, Actions 실행, 실제 AWS 자원 생성을 하지 않았다** - 변경 사항은 전부
로컬 파일에만 있다.

**중요**: 4~5단계에서는 EC2에 파일만 준비하고 **API/Worker 컨테이너를 띄우지 않는다.**
`docker-compose.aws-*.yml`의 `image: ${IMAGE_URI}`가 가리키는 이미지는 8단계(`workflow_dispatch`)
에서 빌드돼 ECR에 처음 올라간다 - 그 전에 `docker compose up`을 시도하면 이미지가 없어 실패한다.
mysql/redis도 사람이 미리 띄울 필요가 없다 - `docker-compose.aws-api.yml`의 `api` 서비스에
`depends_on`(`service_healthy`)으로 선언돼 있어, 8단계에서 `scripts/aws/deploy.sh`가
`docker compose up -d --force-recreate api`를 처음 실행할 때 Compose가 mysql/redis부터 띄우고
헬스체크를 기다린 뒤 api를 기동한다.

1. `aws login`(SSO 재인증). 계정 ID/리전 확인.
2. OIDC provider + GitHub Actions가 assume할 IAM 역할 생성(§3의 `AWS_ROLE_ARN`) - 이 repo만
   신뢰하도록 조건을 좁힌다.
3. VPC/서브넷/보안그룹 2개(API/Worker, §7), EC2 2대 생성(§1 크기, `Project`/`Environment`/
   `Role=api|worker` 태그, §2 IAM 인스턴스 역할 + IMDSv2 hop-limit=2), ECR 리포지토리 생성.
4. **API EC2 파일 준비(컨테이너는 아직 띄우지 않음)**:
   - Docker + Compose 플러그인 설치(Amazon Linux 2023/Ubuntu 공통, Docker 공식 설치 스크립트
     사용):
     ```bash
     curl -fsSL https://get.docker.com | sh
     sudo systemctl enable --now docker
     sudo usermod -aG docker "$USER"   # 적용하려면 재로그인 필요
     docker compose version            # Compose 플러그인이 같이 설치됐는지 확인(필수)
     ```
     `docker compose version`이 실패하면(Compose 플러그인 누락) 이후 `scripts/aws/deploy.sh`의
     모든 `docker compose ...` 호출이 실패한다 - 반드시 여기서 확인하고 넘어간다.
   - `/opt/autique/`에 `docker-compose.aws-api.yml`·`Caddyfile` 배치, `.env`(`API_PRIVATE_IP`/
     `SITE_ADDRESS` - `IMAGE_URI`는 비워두거나 더미 값으로 둔다, 8단계에서 배포 스크립트가 갱신)
     작성, `api.env` 채우기(§3 SSM 파라미터 + 리터럴 `SPRING_DATASOURCE_URL`, §3 "EC2에 직접
     두는 값" 참고).
5. **Worker EC2 파일 준비(컨테이너는 아직 띄우지 않음)**: 같은 방식으로 Docker/Compose 설치 +
   `docker compose version` 확인, `docker-compose.aws-worker.yml` 배치, `worker.env` 채우기
   (DB/Redis 호스트는 API EC2의 **사설 IP**, §3 참고).
6. GitHub Secrets/Variables 입력(§3, `AUTO_DEPLOY`는 아직 설정하지 않거나 `false`로 둔다).
   `.github/workflows/deploy.yml`·`pr.yml`·`scripts/aws/deploy.sh`를 커밋해 **`main`에 반영**한다
   - `workflow_dispatch`는 워크플로 파일이 기본 브랜치에 있어야 GitHub UI/CLI에서 실행할 수 있다.
7. `workflow_dispatch`로 `deploy.yml` **수동 실행**(첫 배포). 테스트 → 이미지 빌드/ECR 푸시 →
   API 배포(SSM이 API EC2에서 `docker compose up -d --force-recreate api` 실행 - mysql/redis가
   `depends_on`으로 함께 기동되고, `application-api.yml`의 `ddl-auto: update`로 JPA 엔티티 기준
   전체 스키마가 자동 생성된 뒤 `/actuator/health` UP까지 확인) → Worker 배포(API가 UP을 확인한
   **뒤에만** 진행 - `application-worker.yml`의 `ddl-auto: validate`가 스키마 부재 시 기동
   자체를 실패시킨다, 의도된 동작). 둘 중 하나라도 실패하면 이전 last-known-good 이미지로
   자동 롤백된다.
   - 배포 후 확인: API EC2에서 `docker exec -it <mysql 컨테이너> mysql -uroot -p -e 'SHOW TABLES;'`
     로 스키마 생성 여부 확인.
8. **Caddy 기동(수동)**: `scripts/aws/deploy.sh`는 `api`/`worker` 서비스만 다루고 `caddy`는
   건드리지 않는다 - API EC2에서 `docker compose -f docker-compose.aws-api.yml up -d caddy`를
   직접 실행한다. `https://<api-공인ip>.sslip.io/actuator/health` 접속 확인(§8 공인 IP 변경
   절차도 참고).
9. `scripts/aws/smoke-analyze.sh`로 실제 analyze 요청 1건이 COMPLETED까지 이어지는지 확인
   (자동 게이트와 분리된 이유는 OpenAI 호출 비용).
10. 정상 확인 후 repo variable `AUTO_DEPLOY=true` → 이후 main 병합 시 자동 배포로 전환.

**롤백 검증**: `scripts/aws/deploy.sh`는 배포 실패 시 SSM 파라미터의 last-known-good SHA로 API/
Worker를 함께 되돌린다. 실제로 검증하려면(배포 후 확인 필요) 일부러 깨지는 이미지를 만들어
배포해 롤백이 실제로 동작하는지 1회 확인하는 것을 권장한다 - 이번 스크립트에는 그런 트리거를
포함하지 않았으니, 필요하면 별도로 준비한다.

> 위 4~10단계는 AWS 접근 권한이 없어 이번 세션에서 실행하지 못했다 - "배포 후 확인 필요".

---

## 6. 카카오 로그인 · 프론트 연결 · CORS

- `POST /api/auth/kakao`(`KakaoUserInfoClient`)는 프론트가 카카오 SDK로 이미 받아온 access
  token을 그대로 Bearer로 전달받아 신원만 확인한다 - **백엔드에는 OAuth 리다이렉트 URI가 없다.**
  API EC2의 공인 IP가 바뀌어도 **카카오 디벨로퍼스 설정을 바꿀 필요가 없다.**
- 프론트가 바꿀 값: **API base URL = `https://<api-공인ip>.sslip.io`**. IP가 바뀔 때마다
  갱신 필요(§8).
- 카카오 디벨로퍼스에 등록할 값(사람이 입력, 백엔드 IP와 무관): **프론트의 사이트 도메인 /
  JavaScript SDK 도메인**. 정확한 값은 프론트 배포 주소가 정해져야 확정된다 - 프론트 오리진을
  등록한다.
- `CORS_ALLOWED_ORIGINS`(API 컨테이너 env, `JwtSecurityConfig.corsConfigurationSource()`) =
  **프론트 오리진**(콤마 구분 가능). 인증이 쿠키가 아니라 Authorization 헤더 기반이라
  `allowCredentials`는 필요 없다. 프론트 오리진이 바뀌지 않는 한, API의 공인 IP가 바뀌어도
  CORS 값은 그대로다.
- `/api/products/analyze`(POST/GET)는 이제 인증 필수다(§5의 보안 변경) - 시연은 판매자가 먼저
  로그인한 뒤 사진 분석을 진행해야 한다. 프론트가 바꿀 것: 이 두 엔드포인트 호출 시
  `Authorization: Bearer <accessToken>` 헤더 필수, 미인증 시 401 응답.

---

## 7. 보안그룹 / 포트 규칙 + 검증

- **API SG 인바운드**: 80·443 from `0.0.0.0/0`(Caddy). **3306·6379는 Worker SG(소스=Worker SG
  ID)에서만** 허용. 22는 미개방(SSM Session Manager로 관리). 8080·8081은 인바운드 없음(Caddy가
  같은 호스트에서 로컬 루프백으로만 프록시 - `docker-compose.aws-api.yml`의 `127.0.0.1:8080:8080`
  등 참고).
- **Worker SG 인바운드**: 없음(SSM으로만 관리). 아웃바운드는 전체 허용(OpenAI/S3/DB/Redis/ECR).
- **3306·6379가 인터넷에 열려 있지 않은지 검증(배포 후 확인 필요)**:
  1. 외부 호스트에서 `nc -vz <api-공인ip> 3306`, `6379` → 타임아웃/거부여야 정상.
  2. `nmap -Pn -p 3306,6379 <api-공인ip>` → filtered/closed.
  3. Worker EC2 안에서 `nc -vz <api-사설ip> 3306`, `6379` → 성공해야 정상.
  4. `aws ec2 describe-security-groups`로 3306/6379 규칙에 `0.0.0.0/0` 소스가 없는지 확인.

---

## 8. 공인 IP 변경 시 재기동 절차

EC2를 중지 후 시작하면 **공인 IP만** 바뀐다(Elastic IP를 안 쓰므로) - **사설 IP는 그대로
유지**되므로 Worker↔API의 DB/Redis 연결(사설 IP 기반)은 영향받지 않는다.

1. 새 공인 IP 확인: `curl http://169.254.169.254/latest/meta-data/public-ipv4`(API EC2 안에서,
   IMDSv2 토큰 필요 - §2 참고) 또는 콘솔.
2. `/opt/autique/.env`의 `SITE_ADDRESS`를 `<새IP-하이픈>.sslip.io`로 갱신.
3. `docker compose -f docker-compose.aws-api.yml up -d caddy` - 새 호스트명으로 Let's Encrypt
   인증서가 자동 재발급된다.
4. `https://<새IP>.sslip.io/actuator/health` 접속 확인.
5. 프론트 담당자에게 새 API base URL 전달 → 프론트 재배포/설정 갱신.
6. 프론트 오리진이 바뀌지 않았다면 **CORS·카카오 콘솔 설정은 그대로 둔다**(§6).
7. Worker는 API 사설 IP를 그대로 쓰므로 보통 아무 것도 바꿀 필요 없다. (드물게 API/Worker
   인스턴스 자체를 재생성한 경우에만 Worker의 `worker.env`에 있는 DB/Redis 호스트를 새 사설 IP로
   갱신하고 재배포한다.)

---

## 9. 배포 후 리허설 (실사용자 3명: 판매자 1 + 입찰자 2)

카카오 계정 3개 필요: 판매자 S, 입찰자 B1, 입찰자 B2(서로 다른 계정).

1. **등록 장면(S)**: 실제 사진 → (S 로그인 후) analyze → `POST /api/products`로 상품·첫 경매
   등록 → 입력한 시작/종료 시각이 그대로 조회되는지 → 시작 시각 이후 `SCHEDULED→LIVE` 전환
   확인. **첫 경매 최소 진행시간 1시간 정책(`AuctionSchedulePolicy.MIN_DURATION`) 그대로** - 종료
   시각은 시작+최소 1시간 이후로 등록한다(정책 변경 없음).
2. **종료 임박 경매(발표 전 미리 준비)**: 발표 중 입찰·낙찰을 보여주려면, 1과 별도로 **발표보다
   충분히 앞서(예: 발표 2~3시간 전)** S가 새 경매를 등록하되 종료 시각을 발표 시점 근처로
   맞춘다(최소 1시간 정책상 시작~종료 간격이 필요해 미리 등록해야 한다).
3. **입찰/낙찰(B1, B2)**: 각자 로그인해 종료 임박 경매에 입찰 → 한 명은 자동입찰 등록 → 종료
   후 낙찰자 확인 → 판매자/낙찰자의 **앱 내 알림 목록**(`NotificationController`) 확인. 푸시
   알림 구현은 범위 밖.
4. **AI 실 처리 확인**: 1의 analyze가 Worker에서 실제로 소비·완료돼 폴링 상태가
   `AWAITING_USER_CONFIRMATION`(COMPLETED 전 단계)이 되는지 `scripts/aws/smoke-analyze.sh` 또는
   프론트로 확인.

> 이 절 전체는 실제 배포된 환경이 있어야 실행 가능하다 - "배포 후 확인 필요".

---

## 10. 발표 후 자원 중지/비용 관리

1. EC2 API·Worker **중지(Stop)** - 컴퓨트·공인 IPv4 과금 정지, EBS(`mysql-data` 포함)는 유지된다
   (**terminate 금지** - 볼륨이 사라지면 시연 데이터도 사라진다).
2. 다음 발표 전 **Start** → §8 절차로 새 공인 IP 반영.
3. 완전히 정리할 때: ECR 이미지 삭제, EC2 terminate, EBS 볼륨/ENI/보안그룹 삭제, SSM 파라미터/
   IAM 역할/OIDC provider 정리. 삭제된 리소스 ID나 테스트용 자격증명은 다음에 재사용하지 않는다.

---

## 11. 남은 막힘 / 후속 검토

- **AWS 자원 생성·실제 배포·URL 검증**: 로컬 자격증명 만료로 이번 세션에서 수행하지 못했다.
- **프론트 저장소 없음**: `CORS_ALLOWED_ORIGINS`·카카오 등록 도메인·프론트 API base URL의 실제
  값은 프론트 배포 주소가 나와야 채울 수 있다.
- **§4의 마켓플레이스 이미지 공개 정책**: 결정 필요(2안 중 택1, 또는 대안 제시).
- **로그인 사용자별 AI 호출 과금 상한**: `POST /api/products/analyze` 인증 필수화로 익명 남용은
  막았지만, 로그인한 사용자가 반복 호출하는 것까지는 막지 않는다. 필요하면 최소한의 요청 제한
  (새 라이브러리 추가는 승인 필요 - CLAUDE.local.md)을 별도로 검토한다.
- **메모리 실측**: `docker stats`로 t3.medium/t3.small 여유를 확인하기 전에는 더 작은 인스턴스로
  낮추지 않는다.
- **CloudWatch 로그 수집**: 이번 범위에 포함하지 않았다 - 필요하면 `awslogs` 로깅 드라이버를
  compose에 추가하는 후속 작업으로 남긴다.
