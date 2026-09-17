import { useCallback, useEffect, useState } from "react";

import { apiFetch } from "../services/apiFetch";
import { isLoggedIn } from "../services/auth";

export interface DriveAuthStatus {
  isAuthenticated: boolean;
  lastSyncTime: string | null;
  authUrl?: string;
}

/** Polls GET /api/v1/rag/sync/status for Google Drive auth state, only while logged in. */
export function useDriveAuthStatus(pollIntervalMs: number = 30000) {
  const [status, setStatus] = useState<DriveAuthStatus | null>(null);

  const refresh = useCallback(async () => {
    if (!isLoggedIn()) return;
    try {
      const response = await apiFetch("/api/v1/rag/sync/status", {}, { redirectOn401: false });
      if (!response.ok) return;
      const data = await response.json();
      setStatus({ isAuthenticated: data.isAuthenticated, lastSyncTime: data.lastSyncTime, authUrl: data.authUrl });
    } catch (error) {
      console.error("Failed to check Google Drive auth status:", error);
    }
  }, []);

  useEffect(() => {
    refresh();
    if (pollIntervalMs <= 0) return;
    const interval = setInterval(refresh, pollIntervalMs);
    return () => clearInterval(interval);
  }, [refresh, pollIntervalMs]);

  return { status, refresh };
}
