import { Navigate, Outlet, useLocation } from "react-router-dom";

import { isLoggedIn } from "../services/auth";

export default function RequireAuth() {
  const location = useLocation();
  if (!isLoggedIn()) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  return <Outlet />;
}
