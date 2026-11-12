<<<<<<< HEAD
# Top-K Frequent Itemset Mining from Uncertain Databases — Java

Cài đặt Java cho đề tài **Top-K Frequent Itemset Mining from Uncertain Databases**. Đọc dataset chuẩn định dạng SPMF, chạy 4 thuật toán so sánh theo mật độ dữ liệu, xuất kết quả top-k kèm thời gian chạy và bộ nhớ sử dụng.

## 1. Bốn thuật toán cài đặt

| Thuật toán | Hướng tiếp cận | Mô tả |
|---|---|---|
| `NaiveApriori` | Horizontal | Baseline kiểu Apriori, quét lại database cho mỗi candidate. Đối chứng cho toàn bộ hướng vertical/TID. |
| `UEclatTopK` | Vertical — U-tidset đầy đủ | Quét DB 1 lần, xây U-tidset (TID + xác suất), mở rộng itemset bằng merge-join, cắt tỉa bằng Q-Heap threshold-raising. |
| `UDiffsetTopK` | Vertical — U-diffset | Biến thể tối ưu bộ nhớ: chỉ lưu TID thô (không kèm xác suất), tính lại expected support qua công thức đệ quy diffset. Lợi thế rõ ở dữ liệu **dense** (phần "mất đi" nhỏ), bất lợi ở **sparse** (phần "mất đi" gần bằng toàn bộ). |
| `DensityAdaptiveTopK` | Thích ứng | Tính `densityScore` (`density.DensityCalculator`) rồi tự động chọn 1 trong 3 thuật toán trên theo 2 ngưỡng (`--lowThreshold`, `--highThreshold`): dense → NaiveApriori, trung bình → UDiffsetTopK, sparse → UEclatTopK. |

## 2. Cấu trúc thư mục

```
topkufp-java/
├── build.sh
├── README.md
├── data/
│   ├── abcd_example.txt      # ví dụ số kiểm chứng lý thuyết 
│   ├── sample_small.txt
│   ├── sample_dense.txt
│   └── sample_sparse.txt
└── src/
    ├── model/        UEntry, Transaction, UncertainDatabase, ItemsetResult
    ├── probability/  ProbabilityGenerator, Uniform..., Gaussian... (sinh xác suất giả lập)
    ├── io/           DatasetReader (đọc SPMF + đọc dataset có xác suất tường minh), ResultWriter
    ├── density/      DensityCalculator (độ đo mật độ dữ liệu)
    ├── algorithm/    Algorithm, NaiveApriori, UEclatTopK, UDiffsetTopK, DensityAdaptiveTopK
    ├── util/         MemoryProfiler (đo bộ nhớ heap xấp xỉ)
    └── app/          Main (điều phối toàn bộ)
```

## 3. Biên dịch & chạy

```bash
./build.sh              # chỉ biên dịch
./build.sh run           # biên dịch + chạy demo dense/sparse
./build.sh verify        # biên dịch + chạy kiểm chứng ví dụ số ABCD (đối chiếu lý thuyết)
```

Hoặc thủ công:

```bash
mkdir -p out
find src -name "*.java" > sources.txt
javac -d out @sources.txt

java -cp out app.Main \
  --datasets=data/sample_dense.txt,data/sample_sparse.txt \
  --k=10 --algorithms=all --output=results \
  --parallel=false --probMin=0.3 --probMax=1.0 --seed=42
```

## 4. Tham số dòng lệnh đầy đủ

| Tham số | Ý nghĩa | Mặc định |
|---|---|---|
| `--datasets` | danh sách file dataset, cách nhau bởi dấu phẩy | (bắt buộc) |
| `--k` | số lượng top-k itemset cần tìm | 10 |
| `--algorithms` | `all` hoặc danh sách trong `{NaiveApriori, UEclatTopK, UDiffsetTopK, DensityAdaptiveTopK}` | all |
| `--output` | thư mục ghi file kết quả | results |
| `--parallel` | `true` để chạy các thuật toán song song | false |
| `--explicitProb` | `true` nếu file dataset đã có xác suất tường minh (`item:prob`) | false |
| `--probMin`, `--probMax` | khoảng sinh xác suất giả lập (chỉ dùng khi `explicitProb=false`) | 0.3, 1.0 |
| `--seed` | seed sinh số ngẫu nhiên (tái lập kết quả) | 42 |
| `--lowThreshold` | dưới ngưỡng này → phân loại SPARSE → dùng UEclatTopK | 0.35 |
| `--highThreshold` | từ ngưỡng này trở lên → phân loại DENSE → dùng NaiveApriori | 0.55 |

## 5. File kết quả xuất ra

- `results_<dataset>_<thuattoan>.txt` — top-k itemset, expected support, **runtime + bộ nhớ xấp xỉ**, số candidate, thông tin bổ sung (chế độ đã chọn, chỉ số mật độ...).
- `summary_<dataset>.txt` — bảng so sánh nhanh runtime/bộ nhớ giữa các thuật toán trên cùng dataset.
- **`benchmark_summary.csv`** — tổng hợp TOÀN BỘ (mọi dataset × mọi thuật toán) kèm `densityScore`, `densityCategory` (DENSE/MEDIUM/SPARSE), runtime, bộ nhớ, số candidate, expected-support trung bình của top-k. 
=======
>>>>>>> 5fa0f96 (update source code)
