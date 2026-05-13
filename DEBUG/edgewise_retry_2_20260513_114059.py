import pandas as pd
from io import StringIO
import json

df = pd.read_csv(StringIO(DATA_CSV))

sleep_col = 'sleep_hours'
perf_col = 'academic_performance'

correlation_matrix = df.corr()
correlation = correlation_matrix.loc[sleep_col, perf_col]

result = {
    "values": [correlation],
    "labels": [f"Correlation between {sleep_col} and {perf_col}"]
}

print(json.dumps(result))