package probability;

import java.util.Random;

/**
 * Sinh xác suất theo phân phối đều U(min, max]. Đây là cách phổ biến nhất
 * trong literature để giả lập uncertain database từ dataset chắc chắn có sẵn.
 */
public class UniformProbabilityGenerator implements ProbabilityGenerator {
    private final double min;
    private final double max;

    public UniformProbabilityGenerator(double min, double max) {
        if (min <= 0 || max > 1 || min > max) {
            throw new IllegalArgumentException("Yêu cầu 0 < min <= max <= 1");
        }
        this.min = min;
        this.max = max;
    }

    @Override
    public double generate(Random rnd) {
        return min + rnd.nextDouble() * (max - min);
    }
}
