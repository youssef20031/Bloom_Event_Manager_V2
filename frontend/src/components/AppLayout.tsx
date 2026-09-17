import { useEffect, useState } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";

import { useDriveAuthStatus } from "../hooks/useDriveAuthStatus";
import { logout } from "../services/auth";
import bloomLogo from "../assets/bloom-logo.png";

type Notification = { type: "success" | "error"; message: string };

/**
 * Shell for the authenticated app: sidebar navigation + main content outlet.
 *
 * Navigation is URL-driven — each sidebar entry is a <NavLink> that owns its own
 * `active`/`aria-current` state, and the routed page renders through <Outlet />.
 * Notifications can arrive two ways: pushed via `location.state.notification`
 * (e.g. the Google Drive OAuth redirect lands on /settings with a state payload),
 * or raised locally when Drive auth is detected as expired.
 */
export default function AppLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { status: driveAuthStatus } = useDriveAuthStatus();
  const [notification, setNotification] = useState<Notification | null>(null);

  const isSettings = location.pathname === "/settings";
  const isAdvisor = location.pathname === "/advisor";

  // Surface a notification handed over through navigation state (e.g. after the
  // OAuth callback redirects to /settings), then clear it so it doesn't
  // reappear on refresh or back-navigation.
  useEffect(() => {
    const stateNotification = (location.state as { notification?: Notification } | null)?.notification;
    if (stateNotification) {
      setNotification(stateNotification);
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location.pathname, location.state, navigate]);

  // If Drive auth is lost while the user is elsewhere, route them to Settings.
  useEffect(() => {
    if (driveAuthStatus?.isAuthenticated === false && !isSettings) {
      setNotification({
        type: "error",
        message: "Google Drive authentication expired. Please reconnect.",
      });
      navigate("/settings");
    }
  }, [driveAuthStatus, isSettings, navigate]);

  const navLinkClass = ({ isActive }: { isActive: boolean }) =>
    `nav-item ${isActive ? "active" : ""}`;

  return (
    <div className="app-container">
      {notification && (
        <div className={`notification ${notification.type}`} role="alert" aria-live="polite">
          {notification.message}
          <button onClick={() => setNotification(null)} aria-label="Close notification">
            ×
          </button>
        </div>
      )}

      {/* Sidebar Navigation */}
      <aside className="app-sidebar">
        <div className="sidebar-header">
          <Link to="/" className="sidebar-brand" aria-label="Go to Bloom home page">
            <img src={bloomLogo} alt="" className="sidebar-logo-mark" />
            <span className="sidebar-logo">Bloom</span>
          </Link>
          <p className="sidebar-subtitle">Event Management</p>
        </div>

        <nav className="sidebar-nav" aria-label="Main navigation">
          <div className="nav-steps">
            <NavLink to="/intake" className={navLinkClass}>
              <span className="nav-icon">
                <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="square" strokeLinejoin="miter">
                  <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"></path>
                  <rect x="8" y="2" width="8" height="4"></rect>
                </svg>
              </span>
              Event Intake Form
            </NavLink>

            <span className="sidebar-step-arrow" aria-hidden="true">
              <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"></line>
                <polyline points="19 12 12 19 5 12"></polyline>
              </svg>
            </span>

            <NavLink to="/advisor" className={navLinkClass}>
              <span className="nav-icon">
                <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="square" strokeLinejoin="miter">
                  <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                </svg>
              </span>
              Event Advisor
            </NavLink>

            <span className="sidebar-step-arrow" aria-hidden="true">
              <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"></line>
                <polyline points="19 12 12 19 5 12"></polyline>
              </svg>
            </span>

            <NavLink to="/proposals" className={navLinkClass}>
              <span className="nav-icon">
                <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="square" strokeLinejoin="miter">
                  <rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
                  <line x1="8" y1="21" x2="16" y2="21"></line>
                  <line x1="12" y1="17" x2="12" y2="21"></line>
                </svg>
              </span>
              Proposals
            </NavLink>
          </div>

          <NavLink to="/settings" className={({ isActive }) => `nav-item nav-item-settings ${isActive ? "active" : ""}`}>
            <span className="nav-icon">
              <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="square" strokeLinejoin="miter">
                <circle cx="12" cy="12" r="3"></circle>
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
              </svg>
            </span>
            Settings
          </NavLink>

          <button
            type="button"
            className="nav-item nav-item-settings"
            onClick={() => {
              logout();
              navigate("/login", { replace: true });
            }}
          >
            <span className="nav-icon" aria-hidden="true">⎋</span>
            Log out
          </button>
        </nav>
      </aside>

      {/* Main Content Area */}
      <div className="app-main">
        <main id="main-content" role="main" className={isAdvisor ? "full-width" : ""}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
