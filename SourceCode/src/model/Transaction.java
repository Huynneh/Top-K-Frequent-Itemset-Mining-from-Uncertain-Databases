package model;

import java.util.HashMap;
import java.util.Map;

/**
 * Một transaction trong uncertain database: TID + danh sách item kèm xác suất.
 */
public class Transaction {
    public final int tid;
    public final int[] items;      // đã sắp xếp tăng dần
    public final double[] probs;   // probs[i] tương ứng items[i]

    // map tra cứu nhanh item -> prob (dùng cho thuật toán quét ngang)
    private Map<Integer, Double> lookup;

    public Transaction(int tid, int[] items, double[] probs) {
        this.tid = tid;
        this.items = items;
        this.probs = probs;
    }

    public double probOf(int item) {
        if (lookup == null) {
            lookup = new HashMap<>();
            for (int i = 0; i < items.length; i++) {
                lookup.put(items[i], probs[i]);
            }
        }
        Double p = lookup.get(item);
        return p == null ? 0.0 : p;
    }

    public int length() {
        return items.length;
    }
}
