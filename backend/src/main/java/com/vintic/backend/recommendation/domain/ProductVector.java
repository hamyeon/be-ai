package com.vintic.backend.recommendation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;

// 상품 임베딩 벡터를 영속화한다.
//
// 기동할 때마다 임베딩 API를 다시 호출하면 비용과 기동 시간이 상품 수에 비례해 늘어난다.
// 한 번 만든 벡터는 상품 정보가 바뀌지 않는 한 그대로이므로 저장해두고 읽어 쓴다.
//
// 벡터는 float[]를 바이트로 직렬화해 BLOB에 넣는다. JSON 문자열로 넣으면 1536차원 기준
// 20KB 넘게 차지하고 파싱 비용도 든다. 대신 사람이 읽을 수 없어서, 어떤 텍스트로 만든
// 벡터인지 sourceText를 같이 남긴다 - 나중에 임베딩 입력을 바꿨을 때 재생성 대상을
// 가려내려면 필요하다.
//
// MySQL에 벡터를 넣는 건 정석이 아니다(전용 Vector DB 대비 검색이 비효율적). 다만
// docs/ai-infra-design.md에서 Redis Vector 도입을 보류하기로 했고, 지금 상품 수에서는
// 애플리케이션 메모리에 올려 코사인 유사도를 계산하는 편이 충분히 빠르다.
@Entity
@Table(name = "product_vectors")
public class ProductVector {

    // 상품당 벡터 하나. 별도 시퀀스를 둘 이유가 없어 productId를 그대로 PK로 쓴다.
    @Id
    @Column(name = "product_id")
    private Long productId;

    // columnDefinition을 명시한다. @Lob만 두면 MySQL에서 TINYBLOB(255바이트)으로 생성돼
    // 1536차원 벡터(6KB)가 들어가지 않는다. (실측: Data too long for column 'vector_bytes')
    @Lob
    @Column(name = "vector_bytes", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] vectorBytes;

    @Column(name = "dimension", nullable = false)
    private int dimension;

    // 이 벡터를 만든 입력 텍스트. 임베딩 재료가 바뀌면 이 값도 달라지므로,
    // 재생성이 필요한 상품을 찾는 기준이 된다.
    @Column(name = "source_text", nullable = false, length = 1000)
    private String sourceText;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProductVector() {
    }

    public static ProductVector of(Long productId, float[] vector, String sourceText) {
        if (productId == null) {
            throw new IllegalArgumentException("상품 ID는 필수입니다.");
        }
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("벡터는 비어 있을 수 없습니다.");
        }

        ProductVector productVector = new ProductVector();
        productVector.productId = productId;
        productVector.vectorBytes = toBytes(vector);
        productVector.dimension = vector.length;
        productVector.sourceText = sourceText;
        productVector.updatedAt = LocalDateTime.now();
        return productVector;
    }

    public float[] toVector() {
        return toFloats(vectorBytes, dimension);
    }

    // 입력 텍스트가 그대로면 벡터도 그대로다. 재생성 대상을 가려낼 때 쓴다.
    public boolean isStale(String currentSourceText) {
        return !sourceText.equals(currentSourceText);
    }

    private static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private static float[] toFloats(byte[] bytes, int dimension) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    public Long getProductId() {
        return productId;
    }

    public int getDimension() {
        return dimension;
    }

    public String getSourceText() {
        return sourceText;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
