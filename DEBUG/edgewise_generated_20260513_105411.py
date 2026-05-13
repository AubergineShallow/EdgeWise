import pandas as pd
from io import StringIO
import json
from scipy.stats import pearsonr

# Assume DATA_CSV is globally available as per instructions
# Example placeholder for demonstration purposes (this will be replaced by the actual global variable)
# DATA_CSV = """age,gender,daily_social_media_hours,platform_usage,sleep_hours,screen_time_before_sleep,academic_performance,physical_activity,social_interaction_level,stress_level,anxiety_level,addiction_level,depression_label
25,male,2.5,Both,7.5,3.0,88.5,4.5,medium,3,2,5,0
30,female,1.0,Instagram,6.0,2.0,82.0,3.0,high,4,3,4,2,0
45,male,3.5,TikTok,5.5,4.0,75.2,2.0,low,2,1,2,1,0
22,female,1.5,Both,8.0,1.5,92.1,5.0,high,1,1,1,1,0
38,male,2.0,Instagram,6.5,2.5,85.0,3.5,medium,3,2,3,2,0
"""

# 1. Load the data
try:
    df = pd.read_csv(StringIO(DATA_CSV))
except Exception as e:
    # In a real scenario, this would handle the actual DATA_CSV loading
    # For this execution, we proceed assuming df is loaded correctly.
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
correlation, p_value = pearsonr(df[sleep_col], df[performance_col])

# 4. Prepare data for charting (Focusing on the relationship)
# For a simple visualization (like a scatter plot proxy), we can use the relationship itself.
# We will prepare data points for a simple bar chart showing the relationship's strength.

# Calculate descriptive statistics for the relationship
mean_sleep = df[sleep_col].mean()
mean_performance = df[performance_col].mean()

# Prepare data for the JSON output (Focusing on the correlation strength and means)
# Since we cannot plot, we will represent the key finding (correlation) and the means.
# For a bar chart requirement, we will use the correlation strength as the primary metric.

# Create a list of values for charting (e.g., the correlation coefficient and the means)
chart_values = [
    correlation,
    mean_sleep,
    mean_performance
]
chart_labels = [
    "Correlation Coefficient (r)",
    "Mean Sleep Hours",
    "Mean Academic Performance"
]

# 5. Output the result as a JSON string
result = {
    "correlation_analysis": {
        "correlation_coefficient": correlation,
        "p_value": p_value,
        "interpretation": "The correlation coefficient indicates the linear relationship between sleep hours and academic performance. A high positive value suggests that as sleep hours increase, academic performance tends to increase."
    },
    "chart_data": {
        "values": chart_values,
        "labels": chart_labels
    }
}

print(json.dumps(result))