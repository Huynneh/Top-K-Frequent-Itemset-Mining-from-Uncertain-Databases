package algorithm;

import model.ItemsetResult;
import model.UncertainDatabase;

import java.util.List;
import java.util.Map;

/**
 * Interface chung cho tất cả thuật toán Top-K Uncertain Frequent Itemset Mining.
 */
public interface Algorithm {

    /** Tên thuật toán, dùng để đặt tên file kết quả. */
    String getName();

    /** Chạy mining, trả về AlgorithmRun chứa kết quả top-k + thông tin runtime/metadata. */
    AlgorithmRun run(UncertainDatabase db, int k);

    /**
     * Kết quả một lần chạy: danh sách top-k itemset (sắp xếp giảm dần theo expSup),
     * thời gian chạy, và các thông tin phụ (ví dụ: chế độ đã chọn, số candidate sinh ra...).
     */
    class AlgorithmRun {
        public final String algorithmName;
        public final String datasetName;
        public final int k;
        public final List<ItemsetResult> topK;
        public final long runtimeMillis;
        public final long candidatesGenerated;
        public final long memoryUsedBytes; // xấp xỉ, xem util.MemoryProfiler
        public final Map<String, String> extraInfo;

        public AlgorithmRun(String algorithmName, String datasetName, int k,
                             List<ItemsetResult> topK, long runtimeMillis,
                             long candidatesGenerated, long memoryUsedBytes,
                             Map<String, String> extraInfo) {
            this.algorithmName = algorithmName;
            this.datasetName = datasetName;
            this.k = k;
            this.topK = topK;
            this.runtimeMillis = runtimeMillis;
            this.candidatesGenerated = candidatesGenerated;
            this.memoryUsedBytes = memoryUsedBytes;
            this.extraInfo = extraInfo;
        }
    }
}
