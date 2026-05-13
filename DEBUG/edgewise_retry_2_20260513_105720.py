import pandas as pd
from io import StringIO
import json
from scipy.stats import pearsonr

# Assume DATA_CSV is globally available as per instructions
# The fix addresses the ImportError by ensuring necessary dependencies (like numpy)
# are handled correctly within the execution environment, and by removing the
# problematic 'pytz' import which was causing the secondary error.

# 1. Load the data
try:
    # Use the globally available DATA_CSV
    df = pd.read_csv(StringIO(DATA_CSV))
except Exception as e:
    # Handle loading error if DATA_CSV is missing or malformed
    print(json.dumps({"error": f"Failed to load data: {e}"}))
    exit()

# 2. Plan the analysis: Correlation between 'sleep_hours' and 'academic_performance'
# Goal: Determine the correlation coefficient (Pearson's r) and calculate the mean/std for visualization data.

# Select the relevant columns
sleep_col = 'sleep_hours'
performance_col = 'academic_performance'

# Check if columns exist (Safety check based on schema)
if sleep_col not in df.columns or performance_col not in df.columns:
    print(json.dumps({"error": "Required columns ('sleep_hours' or 'academic_performance') not found in the data."}))
    exit()

# 3. Calculate correlation
# Calculate Pearson correlation coefficient and p-value
# pearsonr returns (correlation, p_value)
correlation, p_value = pearsonr(df[sleep_col], df[performance_col])

# 4. Prepare data for charting (Focusing on the relationship)
# We will prepare data points for a simple visualization (like a scatter plot proxy)
# We will use the correlation coefficient and the means as the primary metrics.

# Calculate descriptive statistics for the relationship
mean_sleep = df[sleep_col].mean()
mean_performance = df[performance_col].mean()

# Prepare data for the JSON output (Focusing on the correlation strength and means)
# The requirement is to print exactly ONE line of valid JSON with a "values" key containing a list of numbers.
chart_values = [
    correlation,
    mean_sleep,
    mean_performance
]

result = {
    "values": chart_values
}

print(json.dumps(result))