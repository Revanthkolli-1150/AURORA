# AURORA Statistical Anomaly Detection

## 1. Overview
AURORA employs deterministic statistical anomaly detection algorithms behind the unified `AnomalyDetector` abstraction. These detectors evaluate incoming time-series telemetry against an unpolluted historical baseline to classify observations and calculate a normalized anomaly magnitude.

> [!IMPORTANT]
> The anomaly score is a **normalized anomaly magnitude**, bounded strictly in $[0.0, 1.0)$. It is **NOT** a probability, $p$-value, or confidence interval.

---

## 2. Statistical Detectors

### 2.1. Mean / Standard-Deviation Based Anomaly Detection (`Z_SCORE`)
The Z-score detector computes sample statistics over historical observations:

$$\mu = \frac{1}{N} \sum_{i=1}^{N} x_i, \quad \sigma = \sqrt{\frac{1}{N-1} \sum_{i=1}^{N} (x_i - \mu)^2}$$

The standard score for the current observation $x$ is:

$$z = \frac{x - \mu}{\sigma}$$

#### Normalized Score Function:
To prevent premature saturation at $1.0$, AURORA computes the anomaly magnitude using the asymptotic exponential transformation:

$$\text{score} = 1 - \exp\left(-\frac{|z|}{\tau}\right)$$

where $\tau$ is the configured detection threshold (default $\tau = 3.0$).

**Mathematical Properties**:
- $\text{score} = 0$ when $z = 0$.
- Strictly symmetric: $\text{score}(z) = \text{score}(-z)$.
- Strictly monotonically increasing with $|z|$.
- Approaches $1.0$ asymptotically as $|z| \to \infty$, but remains strictly $< 1.0$ for any finite $|z|$.
- Never saturates artificially at $\tau$.

#### Degenerate Case: Zero Variance ($\sigma = 0$)
When all historical samples are identical:
- If $x == \mu$: Observation is `NORMAL`, $z = 0$, $\text{score} = 0.0$.
- If $x \neq \mu$: Observation is `ANOMALOUS`, $z = \text{null}$, $\text{score} = 1.0$.

---

### 2.2. Robust Non-Parametric Detection (`MAD`)
The Median Absolute Deviation (MAD) detector provides outlier-resilient detection that is immune to historical metric spikes:

$$\tilde{x} = \text{median}(X)$$

$$\text{AD}_i = |x_i - \tilde{x}|$$

$$\text{MAD} = \text{median}(\text{AD})$$

The robust scale estimator (consistent with normal distributions) is:

$$\hat{\sigma}_{\text{MAD}} = 1.4826 \times \text{MAD}$$

The robust Z-score is:

$$z_{\text{robust}} = \frac{|x - \tilde{x}|}{\hat{\sigma}_{\text{MAD}}}$$

#### Normalized Score Function:
$$\text{score} = 1 - \exp\left(-\frac{z_{\text{robust}}}{\tau}\right)$$

#### Degenerate Case: Zero MAD ($\text{MAD} = 0$)
- If $x == \tilde{x}$: Observation is `NORMAL`, $\text{score} = 0.0$.
- If $x \neq \tilde{x}$: Observation is `ANOMALOUS`, $\text{score} = 1.0$.

---

## 3. Configuration & Operational Contracts
- **Baseline Isolation**: The latest observation being evaluated is strictly excluded from its own historical baseline to prevent baseline pollution.
- **Minimum Sample Count**: Both detectors enforce a minimum of 10 historical samples. Datasets with fewer than 10 observations return `AnomalyStatus.INSUFFICIENT_DATA`.
- **Active Detector Selection**:
  ```yaml
  aurora:
    intelligence:
      anomaly:
        detector: Z_SCORE # Options: Z_SCORE, MAD (case-insensitive)
        default-threshold: 3.0
        min-samples: 10
  ```
