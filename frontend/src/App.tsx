import "./App.css";

import { Navigate, Route, Routes, useNavigate } from "react-router-dom";

import AppLayout from "./components/AppLayout";
import EventIntakeForm from "./components/EventIntakeForm";
import ChatInterface from "./components/ChatInterface";
import ProposalGenerator from "./components/ProposalGenerator";
import Settings from "./components/Settings";
import LandingPage from "./components/LandingPage";
import LoginPage from "./components/LoginPage";
import RequireAuth from "./components/RequireAuth";
import { useTheme } from "./hooks/useTheme";

type NotificationState = { notification: { type: "success" | "error"; message: string } };

/**
 * Landing page route ("/").
 *
 * The Google Drive OAuth callback redirects the browser to "/?auth=success"
 * (or "/?auth=error"). Rather than flash the landing page, detect that param
 * up front and hand the user straight to /settings, passing the notification
 * through router state for AppLayout to display.
 */
function LandingRoute() {
  const navigate = useNavigate();
  const { theme, toggleTheme } = useTheme();

  const authStatus = new URLSearchParams(window.location.search).get("auth");
  if (authStatus === "success" || authStatus === "error") {
    const notification: NotificationState["notification"] =
      authStatus === "success"
        ? { type: "success", message: "Google Drive connected successfully!" }
        : { type: "error", message: "Failed to connect Google Drive." };
    return <Navigate to="/settings" replace state={{ notification }} />;
  }

  return (
    <LandingPage
      onGetStarted={() => navigate("/intake")}
      theme={theme}
      toggleTheme={toggleTheme}
    />
  );
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<LandingRoute />} />
      <Route path="/login" element={<LoginPage />} />

      {/* Authenticated app shell — sidebar navigation + routed pages */}
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route path="/intake" element={<EventIntakeForm />} />
          <Route path="/proposals" element={<ProposalGenerator />} />
          <Route
            path="/advisor"
            element={
              <div style={{ width: "100%", height: "100%", display: "flex", flexDirection: "column" }}>
                <ChatInterface />
              </div>
            }
          />
          <Route path="/settings" element={<Settings />} />
        </Route>
      </Route>

      {/* Unknown paths fall back to the landing page */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default App;
