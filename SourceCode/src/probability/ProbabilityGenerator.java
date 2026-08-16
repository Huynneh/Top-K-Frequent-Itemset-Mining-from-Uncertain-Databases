package probability;

import java.util.Random;

/**
 * Sinh giá trị xác suất tồn tại p(i,t) cho mỗi item trong mỗi transaction,
 * vì các file dataset gốc (định dạng SPMF/FIMI) chỉ có item, chưa có xác suất.
 */
public interface ProbabilityGenerator {
    double generate(Random rnd);
}
