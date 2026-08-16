#!/bin/bash
# Script tien dung: bien dich va chay thu.
# Cach dung:
#   ./build.sh              -> chi bien dich
#   ./build.sh run           -> bien dich + chay thu voi dataset dense/sparse mau
#   ./build.sh verify        -> bien dich + chay kiem chung vi du so ABCD (doi chieu voi ly thuyet)

set -e
cd "$(dirname "$0")"

echo ">> Bien dich..."
rm -rf out
mkdir -p out
find src -name "*.java" > sources.txt
javac -d out @sources.txt
echo ">> Bien dich xong. Class files nam trong ./out"

if [ "$1" == "run" ]; then
  echo ">> Chay thu voi dataset mau (dense + sparse, 4 thuat toan)..."
  java -cp out app.Main \
    --datasets=data/sample_dense.txt,data/sample_sparse.txt \
    --k=10 --algorithms=all --output=results \
    --parallel=false --probMin=0.3 --probMax=1.0 --seed=42
  echo ">> Ket qua nam trong ./results (xem them results/benchmark_summary.csv)"
fi

if [ "$1" == "verify" ]; then
  echo ">> Chay kiem chung vi du so ABCD (doi chieu voi tai lieu ly thuyet)..."
  java -cp out app.Main \
    --datasets=data/abcd_example.txt --explicitProb=true \
    --k=3 --algorithms=all --output=results
  echo ">> Ket qua top-3 phai la: {A}=3.0  {B}=2.7  {C}=2.6 (item D bi cat tia)"
  echo ">> Xem ./results/results_abcd_example.txt_*.txt de doi chieu"
fi
