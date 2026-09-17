import { useState } from "react";
import { useDriveAuthStatus } from "../hooks/useDriveAuthStatus";
import { apiFetch } from "../services/apiFetch";
import "./Settings.css";

export default function Settings() {
    const { status: authStatus } = useDriveAuthStatus();
    const [loading, setLoading] = useState(false);

    const handleConnect = async () => {
        if (authStatus?.authUrl) {
            window.location.href = authStatus.authUrl;
            return;
        }
        const res = await apiFetch("/api/v1/rag/sync/auth?force=true");
        if (res.ok) {
            const data = await res.json();
            if (data.authUrl) window.location.href = data.authUrl;
        }
    };

    return (
        <div className="settings-container">
            <h2>Settings</h2>

            <div className="setting-card">
                <h3>Google Drive Integration</h3>
                <p>Sync your PDFs from Google Drive automatically.</p>

                <div className="status-row">
                    <span className={`status-badge ${authStatus?.isAuthenticated ? "connected" : "disconnected"}`}>
                        {authStatus?.isAuthenticated ? "✓ Connected" : "⚠ Not Connected"}
                    </span>
                    {authStatus?.lastSyncTime && (
                        <span className="last-sync">
                            Last sync: {new Date(authStatus.lastSyncTime).toLocaleString()}
                        </span>
                    )}
                </div>

                <div className="button-group">
                    <button
                        className="btn btn-primary connect-btn"
                        onClick={handleConnect}
                    >
                        {authStatus?.isAuthenticated ? "Reconnect Google Drive" : "Connect Google Drive"}
                    </button>

                    <button
                        className="btn btn-secondary copy-btn"
                        onClick={() => {
                            if (authStatus?.authUrl) {
                                navigator.clipboard.writeText(authStatus.authUrl);
                                alert("Auth URL copied to clipboard!");
                            } else {
                                alert("Auth URL not available yet. Try connecting first.");
                            }
                        }}
                    >
                        📋 Copy Auth URL
                    </button>
                </div>
            </div>
        </div>
    );
}
