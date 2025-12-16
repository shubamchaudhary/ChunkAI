import { useState, useEffect } from 'react';
import { subscribeToConnectionState, wakeUpServer } from '../services/api';

/**
 * Connection Status Banner
 * Shows when the server is waking up (cold start on Render free tier)
 */
export default function ConnectionStatus() {
  const [state, setState] = useState({
    isConnected: false,
    isWakingUp: false,
  });
  const [elapsedTime, setElapsedTime] = useState(0);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    // Subscribe to connection state changes
    const unsubscribe = subscribeToConnectionState(setState);

    // Try to wake up server on mount
    wakeUpServer();

    return unsubscribe;
  }, []);

  // Timer for elapsed time during wake up
  useEffect(() => {
    let interval;
    if (state.isWakingUp) {
      setElapsedTime(0);
      setDismissed(false);
      interval = setInterval(() => {
        setElapsedTime(prev => prev + 1);
      }, 1000);
    }
    return () => clearInterval(interval);
  }, [state.isWakingUp]);

  // Don't show if connected or dismissed
  if (state.isConnected || (!state.isWakingUp && !dismissed)) {
    return null;
  }

  if (dismissed) {
    return null;
  }

  return (
    <div className="fixed top-0 left-0 right-0 z-50">
      <div className="bg-gradient-to-r from-amber-500 to-orange-500 text-white px-4 py-3 shadow-lg">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <div className="flex items-center space-x-3">
            {/* Animated spinner */}
            <div className="relative">
              <div className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin"></div>
            </div>

            <div className="flex flex-col sm:flex-row sm:items-center sm:space-x-2">
              <span className="font-medium">Server is starting up...</span>
              <span className="text-amber-100 text-sm">
                {elapsedTime > 0 && `(${elapsedTime}s)`}
                {elapsedTime > 10 && ' - Free tier cold starts can take 30-60 seconds'}
              </span>
            </div>
          </div>

          <button
            onClick={() => setDismissed(true)}
            className="text-white/80 hover:text-white p-1"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Progress bar */}
        <div className="max-w-7xl mx-auto mt-2">
          <div className="h-1 bg-white/20 rounded-full overflow-hidden">
            <div
              className="h-full bg-white/60 transition-all duration-1000 ease-out"
              style={{
                width: `${Math.min((elapsedTime / 60) * 100, 95)}%`,
              }}
            ></div>
          </div>
        </div>
      </div>
    </div>
  );
}
