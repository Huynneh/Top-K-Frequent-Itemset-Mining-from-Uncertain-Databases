package probability;

import java.util.Random;

/**
 * Sinh xác suất theo phân phối Gaussian (mean, std), cắt (clip) về khoảng (0,1].
 * Dùng khi muốn mô phỏng dữ liệu "gần chắc chắn" (mean cao, std thấp)
 * hoặc "rất nhiễu" (std cao) để kiểm tra độ nhạy của thuật toán.
 */
public class GaussianProbabilityGenerator implements ProbabilityGenerator {
    private final double mean;
    private final double std;
    private static final double EPS = 1e-6;

    public GaussianProbabilityGenerator(double mean, double std) {
        this.mean = mean;
        this.std = std;
    }

    @Override
    public double generate(Random rnd) {
        double p = mean + rnd.nextGaussian() * std;
        if (p < EPS) p = EPS;
        if (p > 1.0) p = 1.0;
        return p;
    }
}
