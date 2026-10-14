'use strict';

function timeoutError(timeoutMs) {
  const error = new Error(`searchQuery exceeded MAX_INFLIGHT_MS hard timeout (${timeoutMs}ms)`);
  error.code = 'provider_timeout';
  error.providerOutcomeUnknown = true;
  error.retrySafe = false;
  return error;
}

function runWithHardTimeout(execute, options = {}) {
  if (typeof execute !== 'function') throw new TypeError('execute must be a function');
  const timeoutMs = Math.max(1, Number(options.timeoutMs) || 1);
  const abortGraceMs = Math.max(1, Number(options.abortGraceMs) || 15000);
  const controller = new AbortController();
  let timeoutHandle = null;
  let abortWatchdog = null;
  let operationSettled = false;

  const operationPromise = Promise.resolve().then(() => execute(controller.signal));
  const workerReleasePromise = operationPromise.then(() => undefined, () => undefined);
  workerReleasePromise.then(() => {
    operationSettled = true;
    if (abortWatchdog) clearTimeout(abortWatchdog);
  });

  const timeoutPromise = new Promise((_, reject) => {
    timeoutHandle = setTimeout(() => {
      const error = timeoutError(timeoutMs);
      error.workerReleasePromise = workerReleasePromise;
      try {
        if (typeof options.onTimeout === 'function') options.onTimeout(error);
      } catch (markerError) {
        error.quarantineMarkerError = markerError;
      }
      controller.abort(error);
      abortWatchdog = setTimeout(() => {
        if (operationSettled) return;
        const details = {
          code: 'provider_timeout_abort_unsettled',
          timeoutMs,
          abortGraceMs,
          message: 'Timed-out DeepSeek operation did not settle after abort; bridge must fail-stop'
        };
        try {
          if (typeof options.onAbortTimeout === 'function') options.onAbortTimeout(details);
        } catch (_) {}
      }, abortGraceMs);
      if (typeof abortWatchdog.unref === 'function') abortWatchdog.unref();
      reject(error);
    }, timeoutMs);
  });

  return Promise.race([operationPromise, timeoutPromise]).finally(() => {
    if (timeoutHandle) clearTimeout(timeoutHandle);
  });
}

module.exports = { runWithHardTimeout };
