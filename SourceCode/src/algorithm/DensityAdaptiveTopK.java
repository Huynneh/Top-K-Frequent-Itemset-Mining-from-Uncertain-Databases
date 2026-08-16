package algorithm;

import density.DensityCalculator;
import java.util.LinkedHashMap;
import java.util.Map;
import model.UncertainDatabase;

/**
 * Thuật toán đề xuất chính của luận văn: tự động chọn 1 trong 3 chiến lược mining
 * dựa trên độ đo mật độ dữ liệu (DensityCalculator), thay vì cố định 1 cách tiếp cận:
 *
 *   - densityScore rất cao (>= highThreshold)  -> HORIZONTAL (NaiveApriori)
 *     Dữ liệu cực kỳ dense: nhiều prefix trùng nhau, tiếp cận kiểu cây/gộp có lợi
 *     (bản demo dùng NaiveApriori đại diện)
 *
 *   - densityScore trung bình (giữa 2 ngưỡng)  -> VERTICAL-DIFFSET (UDiffsetTopK)
 *     Dữ liệu dense vừa phải: diffset (phần chênh lệch) nhỏ hơn tidset đầy đủ,
 *     tận dụng được lợi thế bộ nhớ + tốc độ
 *
 *   - densityScore thấp (< lowThreshold)        -> VERTICAL-TIDSET (UEclatTopK)
 *     Dữ liệu sparse: tidset ngắn, giao (intersection) trực tiếp rẻ hơn diffset
 *     (vì diffset lúc này gần bằng tidset đầy đủ, không còn lợi thế).
 *
 * 
 */
public class DensityAdaptiveTopK implements Algorithm {

    private final double lowThreshold;   // dưới ngưỡng này -> sparse -> tidset
    private final double highThreshold;  // trên ngưỡng này -> dense -> horizontal

    public DensityAdaptiveTopK(double lowThreshold, double highThreshold) {
        if (lowThreshold > highThreshold) {
            throw new IllegalArgumentException("lowThreshold phải <= highThreshold");
        }
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
    }

    /** Constructor tương thích ngược: 1 ngưỡng duy nhất -> chỉ phân dense/sparse (2 mode). */
    public DensityAdaptiveTopK(double singleThreshold) {
        this(singleThreshold, singleThreshold);
    }

    public DensityAdaptiveTopK() {
        this(0.35, 0.55); // mặc định: 3 vùng, cần hiệu chỉnh thực nghiệm cho luận văn
    }

    @Override
    public String getName() {
        return "DensityAdaptiveTopK";
    }

    @Override
    public AlgorithmRun run(UncertainDatabase db, int k) {
        long start = System.currentTimeMillis();

        DensityCalculator density = new DensityCalculator(db);
        double ds = density.densityScore;

        Algorithm chosen;
        String modeLabel;
        if (ds >= highThreshold) {
            chosen = new NaiveApriori();
            modeLabel = "HORIZONTAL (NaiveApriori) - vung DENSE";
        } else if (ds < lowThreshold) {
            chosen = new UEclatTopK();
            modeLabel = "VERTICAL-TIDSET (UEclatTopK) - vung SPARSE";
        } else {
            chosen = new UDiffsetTopK();
            modeLabel = "VERTICAL-DIFFSET (UDiffsetTopK) - vung TRUNG BINH";
        }

        AlgorithmRun innerRun = chosen.run(db, k);

        long elapsed = System.currentTimeMillis() - start;

        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("densityMetrics", density.toString());
        extra.put("lowThreshold", String.valueOf(lowThreshold));
        extra.put("highThreshold", String.valueOf(highThreshold));
        extra.put("chosenMode", modeLabel);
        extra.putAll(innerRun.extraInfo);

        return new AlgorithmRun(getName(), db.name, k, innerRun.topK, elapsed,
                innerRun.candidatesGenerated, innerRun.memoryUsedBytes, extra);
    }
}
