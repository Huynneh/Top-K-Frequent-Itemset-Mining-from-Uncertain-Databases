package density;

import model.Transaction;
import model.UncertainDatabase;

import java.util.HashMap;
import java.util.Map;

/**
 * Tính độ đo mật độ dữ liệu (Density Score - DS) cho uncertain database,
 * dùng để quyết định chọn chế độ HORIZONTAL hay VERTICAL trong DensityAdaptiveTopK.
 *
 * DS = w1 * structDensity_norm + w2 * avgTidLen_norm + w3 * meanProb
 */
public class DensityCalculator {

    public final double structDensity;   // (avg transaction length) / (số item riêng biệt)
    public final double avgTidLenNorm;    // (avg |tidset(item)|) / (số transaction)  -> trong [0,1]
    public final double meanProb;         // trung bình p(i,t) trên toàn database
    public final double stdProb;          // độ lệch chuẩn p(i,t)
    public final double densityScore;     // điểm mật độ tổng hợp, dùng để ra quyết định

    private static final double W1 = 0.4;
    private static final double W2 = 0.4;
    private static final double W3 = 0.2;

    public DensityCalculator(UncertainDatabase db) {
        int numItems = Math.max(1, db.numDistinctItems);
        int numTrans = Math.max(1, db.size());

        double avgLen = db.avgTransactionLength();
        this.structDensity = avgLen / numItems;

        Map<Integer, Integer> itemOccurCount = new HashMap<>();
        double probSum = 0;
        long probCount = 0;

        for (Transaction t : db.getTransactions()) {
            for (int i = 0; i < t.items.length; i++) {
                itemOccurCount.merge(t.items[i], 1, Integer::sum);
                probSum += t.probs[i];
                probCount++;
            }
        }

        double avgTidLen = 0;
        if (!itemOccurCount.isEmpty()) {
            long sum = 0;
            for (int c : itemOccurCount.values()) sum += c;
            avgTidLen = (double) sum / itemOccurCount.size();
        }
        this.avgTidLenNorm = avgTidLen / numTrans;

        this.meanProb = probCount == 0 ? 0 : probSum / probCount;

        double varSum = 0;
        if (probCount > 0) {
            for (Transaction t : db.getTransactions()) {
                for (double p : t.probs) {
                    varSum += (p - meanProb) * (p - meanProb);
                }
            }
        }
        this.stdProb = probCount == 0 ? 0 : Math.sqrt(varSum / probCount);

        this.densityScore = W1 * clamp01(structDensity) + W2 * clamp01(avgTidLenNorm) + W3 * clamp01(meanProb);
    }

    private double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    @Override
    public String toString() {
        return String.format(
                "structDensity=%.4f, avgTidLenNorm=%.4f, meanProb=%.4f, stdProb=%.4f, densityScore=%.4f",
                structDensity, avgTidLenNorm, meanProb, stdProb, densityScore);
    }
}
