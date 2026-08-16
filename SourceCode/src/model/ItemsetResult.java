package model;

/**
 * Một itemset trong kết quả Top-K, kèm expected support của nó.
 */
public class ItemsetResult implements Comparable<ItemsetResult> {
    public final int[] itemset; // đã sắp xếp tăng dần
    public final double expSup;

    public ItemsetResult(int[] itemset, double expSup) {
        this.itemset = itemset;
        this.expSup = expSup;
    }

    /** So sánh tăng dần theo expSup -> dùng để làm min-heap (PriorityQueue). */
    @Override
    public int compareTo(ItemsetResult o) {
        return Double.compare(this.expSup, o.expSup);
    }

    public String itemsetString() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < itemset.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(itemset[i]);
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return itemsetString() + "  expSup=" + String.format("%.6f", expSup);
    }
}
