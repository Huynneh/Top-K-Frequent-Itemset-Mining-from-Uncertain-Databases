package model;

/**
 * Một phần tử trong U-tidset: (TID, xác suất tồn tại của item tại transaction đó).
 */
public class UEntry {
    public final int tid;
    public final double prob;

    public UEntry(int tid, double prob) {
        this.tid = tid;
        this.prob = prob;
    }

    @Override
    public String toString() {
        return "(" + tid + "," + String.format("%.4f", prob) + ")";
    }
}
