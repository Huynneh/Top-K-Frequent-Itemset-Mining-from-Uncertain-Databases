package io;

import model.Transaction;
import model.UncertainDatabase;
import probability.ProbabilityGenerator;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Đọc file dataset dạng transaction database "chắc chắn" (chưa có xác suất).
 *
 * Định dạng hỗ trợ (giống chuẩn SPMF / FIMI):
 *   Mỗi dòng là một transaction, các item là số nguyên, cách nhau bởi khoảng trắng.
 *   Ví dụ:
 *     1 3 5
 *     2 3
 *     1 2 3 4
 *
 *   Dòng trống hoặc bắt đầu bằng '#' hoặc '%' hoặc '@' sẽ được bỏ qua (comment/metadata).
 *
 * Sau khi đọc, mỗi item trong mỗi transaction sẽ được gắn thêm một xác suất
 * existential p(i,t) sinh ngẫu nhiên bằng ProbabilityGenerator, vì file gốc
 * chưa có thông tin này.
 */
public class DatasetReader {

    /**
     * Đọc dataset từ file và gắn xác suất giả lập.
     *
     * @param filePath   đường dẫn file dataset
     * @param probGen    bộ sinh xác suất
     * @param seed       seed để đảm bảo tái lập kết quả (reproducibility)
     */
    public static UncertainDatabase read(Path filePath, ProbabilityGenerator probGen, long seed) throws IOException {
        Random rnd = new Random(seed);
        List<Transaction> transactions = new ArrayList<>();
        Set<Integer> distinctItems = new HashSet<>();

        String datasetName = filePath.getFileName().toString();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath.toFile()))) {
            String line;
            int tid = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("%") || line.startsWith("@")) {
                    continue;
                }
                String itemPart = line.contains(":") ? line.substring(0, line.indexOf(':')) : line;
                String[] tokens = itemPart.trim().split("\\s+");

                List<Integer> itemList = new ArrayList<>();
                for (String tok : tokens) {
                    if (tok.isEmpty()) continue;
                    try {
                        int item = Integer.parseInt(tok);
                        if (item >= 0) {
                            itemList.add(item);
                        }
                    } catch (NumberFormatException e) {
                        // token không phải số nguyên -> bỏ qua
                    }
                }
                if (itemList.isEmpty()) continue;

                itemList.sort(null);

                int n = itemList.size();
                int[] items = new int[n];
                double[] probs = new double[n];
                for (int i = 0; i < n; i++) {
                    items[i] = itemList.get(i);
                    probs[i] = probGen.generate(rnd);
                    distinctItems.add(items[i]);
                }

                transactions.add(new Transaction(tid, items, probs));
                tid++;
            }
        }

        return new UncertainDatabase(datasetName, transactions, distinctItems.size());
    }

    /**
     * Đọc dataset đã có SẴN xác suất tường minh trong file (không sinh ngẫu nhiên).
     *
     * Định dạng: mỗi dòng là 1 transaction, các item viết dạng "item:prob",
     * cách nhau bởi khoảng trắng. Item ở đây có thể là số nguyên hoặc chuỗi chữ
     * (ví dụ "A:0.8 B:0.6 C:0.9"), sẽ được ánh xạ sang số nguyên nội bộ.
     * Dùng để tái lập chính xác các ví dụ số trong lý thuyết (vd A,B,C,D...).
     *
     * @return UncertainDatabase và bảng ánh xạ tên item gốc -> id nội bộ (để in kết quả dễ đọc)
     */
    public static ReadResult readWithExplicitProbabilities(Path filePath) throws IOException {
        List<Transaction> transactions = new ArrayList<>();
        Map<String, Integer> itemNameToId = new LinkedHashMap<>();
        String datasetName = filePath.getFileName().toString();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath.toFile()))) {
            String line;
            int tid = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("%") || line.startsWith("@")) {
                    continue;
                }
                String[] tokens = line.split("\\s+");
                List<Integer> items = new ArrayList<>();
                List<Double> probs = new ArrayList<>();

                for (String tok : tokens) {
                    int sep = tok.lastIndexOf(':');
                    if (sep < 0) continue; // token không đúng định dạng item:prob -> bỏ qua
                    String name = tok.substring(0, sep);
                    double prob;
                    try {
                        prob = Double.parseDouble(tok.substring(sep + 1));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    int id = itemNameToId.computeIfAbsent(name, x -> itemNameToId.size() + 1);
                    items.add(id);
                    probs.add(prob);
                }
                if (items.isEmpty()) continue;

                // sắp xếp theo id tăng dần để đảm bảo canonical order
                Integer[] order = new Integer[items.size()];
                for (int i = 0; i < order.length; i++) order[i] = i;
                Arrays.sort(order, (a, b) -> Integer.compare(items.get(a), items.get(b)));

                int n = items.size();
                int[] itemArr = new int[n];
                double[] probArr = new double[n];
                for (int i = 0; i < n; i++) {
                    itemArr[i] = items.get(order[i]);
                    probArr[i] = probs.get(order[i]);
                }
                transactions.add(new Transaction(tid, itemArr, probArr));
                tid++;
            }
        }

        UncertainDatabase db = new UncertainDatabase(datasetName, transactions, itemNameToId.size());
        return new ReadResult(db, itemNameToId);
    }

    /** Gói kết quả đọc dataset tường minh, kèm bảng ánh xạ tên item gốc -> id nội bộ. */
    public static class ReadResult {
        public final UncertainDatabase database;
        public final Map<String, Integer> itemNameToId;

        public ReadResult(UncertainDatabase database, Map<String, Integer> itemNameToId) {
            this.database = database;
            this.itemNameToId = itemNameToId;
        }
    }
}
