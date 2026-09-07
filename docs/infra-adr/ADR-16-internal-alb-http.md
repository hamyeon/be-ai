# ADR-16 Internal ALB + HTTP, 공개 HTTPS 미적용

**Decision**: 실험 환경 진입점을 Internal ALB + HTTP 80으로 두고, 도메인·Route53·ACM·443을 만들지 않는다.

**Problem**: 다중 API 인스턴스 앞에 고정 진입점이 필요하지만, 도메인 구매와 인증서 관리는 이번 스프린트의 검증 목표(비동기·정합성·확장·복구)와 무관하고 비용·시간이 든다.

**Alternatives**:
1. Public ALB + 도메인 + ACM + HTTPS — 실제 서비스와 동일하지만 도메인 비용, Hosted Zone $0.50/월, 설정 반나절.
2. ALB 없이 EC2 public IP 직접 호출 — 다중 인스턴스 분산·장애 전환 검증 불가.
3. Internal ALB + HTTP (선택).

**Choice**: 3번.

**Reason**: 부하 생성기(Load EC2)가 같은 VPC 안에 있으므로 외부 진입점이 필요 없다. ALB의 분산·health check·target 전환 동작은 internal이어도 동일하게 검증된다.

**Trade-off**: 브라우저나 외부에서 데모 불가. VPC 내부 ALB→API 구간이 평문.

**Current Mitigation**: 공개 ingress 없음(SG inbound는 Load SG만). SSH 대신 SSM. 합성 데이터만 사용. JWT·Presigned URL TTL 짧게. 전체 URL 로그 금지.

**Residual Risk**: VPC 내부 트래픽 비암호화. 실제 사용자 데이터가 들어가면 부적합.

**Future**: 외부 데모가 필요하면 도메인 + ACM + 443 listener + 80→443 redirect 추가. 앱 코드 변경 없음.