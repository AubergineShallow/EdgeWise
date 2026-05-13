import pandas as pd
from io import StringIO
import json

DATA_CSV = """age,gender,daily_social_media_hours,platform_usage,sleep_hours,screen_time_before_sleep,academic_performance,physical_activity,social_interaction_level,stress_level,anxiety_level,addiction_level,depression_label
14,male,7.9,Instagram,7.4,2.9,3.01,1.5,low,2,2,1,0
19,female,1.9,TikTok,8.0,2.9,3.22,0.8,high,8,1,10,0
15,male,7.4,TikTok,6.9,1.6,3.48,0.8,medium,1,7,9,0
15,female,4.7,Both,4.9,3.0,2.37,1.4,medium,3,5,2,0
"""

df = pd.read_csv(StringIO(DATA_CSV))

correlation_matrix = df.corr()
sleep_performance_corr = correlation_matrix.loc['sleep_hours', 'academic_performance']

result = {
    "values": [sleep_performance_corr],
    "labels": ["sleep_hours vs academic_performance correlation"]
}

print(json.dumps(result))