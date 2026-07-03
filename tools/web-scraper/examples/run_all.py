"""
Run all four Crawl4AI examples in sequence and report a final summary.
Acts as the verification step called out in the README.
"""
import subprocess
import sys
from pathlib import Path

EXAMPLES = Path(__file__).resolve().parent
PYTHON = sys.executable


def run(label: str) -> bool:
    print(f"\n===== {label} =====")
    rc = subprocess.call([PYTHON, str(EXAMPLES / f"{label}.py")])
    print(f"--- {label} exit: {rc} ---")
    return rc == 0


def main() -> int:
    results = {name: run(name) for name in [
        "01_basic_crawl",
        "02_structured_extraction",
        "03_antibot_and_proxy",
        "04_deep_crawl_and_export",
    ]}
    print("\n========= SUMMARY =========")
    for name, ok in results.items():
        print(f"  {name:<32} {'PASS' if ok else 'FAIL'}")
    return 0 if all(results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
