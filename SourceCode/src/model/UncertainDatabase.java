package model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Toàn bộ uncertain database sau khi đọc file + gắn xác suất.
 */
public class UncertainDatabase {
    public final String name;
    public final List<Transaction> transactions;
    public final int numDistinctItems;

    public UncertainDatabase(String name, List<Transaction> transactions, int numDistinctItems) {
        this.name = name;
        this.transactions = transactions;
        this.numDistinctItems = numDistinctItems;
    }

    public int size() {
        return transactions.size();
    }

    /** Tập tất cả item riêng biệt xuất hiện trong database. */
    public Set<Integer> distinctItems() {
        Set<Integer> items = new HashSet<>();
        for (Transaction t : transactions) {
            for (int it : t.items) items.add(it);
        }
        return items;
    }

    /** Độ dài trung bình transaction. */
    public double avgTransactionLength() {
        if (transactions.isEmpty()) return 0;
        long sum = 0;
        for (Transaction t : transactions) sum += t.length();
        return (double) sum / transactions.size();
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }
}
