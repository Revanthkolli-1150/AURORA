# AURORA Intelligence & AI Architecture

The AURORA Intelligence layer consists of specialized analytical engines:

## 1. Anomaly Engine (`intelligence/anomaly-engine`)
- Real-time rolling z-score and EWMA (Exponentially Weighted Moving Average) anomaly scoring.
- Isolation Forests for multi-dimensional telemetry anomaly detection.

## 2. Prediction Engine (`intelligence/prediction-engine`)
- Time-series autoregression forecasting remaining error budget hours under current burn rates.
- Trend detection to predict degradation before users experience service outage.

## 3. RCA Engine (`intelligence/rca-engine`)
- Causal inference across service topology graphs.
- Trace tree correlation to isolate the root node responsible for downstream cascading latency.

## 4. AI Copilot Engine (`intelligence/ai-engine`)
- LLM-powered incident summarization and blast radius assessment.
- Interactive runbook recommendation and conversational root-cause debugging.
