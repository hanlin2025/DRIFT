import { BrowserRouter, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { AdminRoute } from './admin/UserManagementPage';
import './admin/admin.css';
import './shipment/importer-link.css';
import { AuthLayout } from './components/AuthLayout';
import { LoginPage } from './login/LoginPage';
import { clearSession, homePath, isExpired, readSession } from './session/session';
import { SignupPage } from './signup/SignupPage';
import { WorkspaceRoute } from './workspace/WorkspaceRoute';

function SignupRoute() {
  const navigate = useNavigate();
  return <SignupPage onRegistered={account => navigate('/login', {
    replace: true, state: { registered: true, email: account.email },
  })} />;
}

function HomeRedirect() {
  const saved = readSession();
  if (saved && !isExpired(saved.expiresAt)) return <Navigate to={homePath(saved.role)} replace />;
  if (saved) clearSession();
  return <Navigate to="/login" replace />;
}

export function AppRoutes() {
  return <Routes>
    <Route path="/signup" element={<AuthLayout><SignupRoute /></AuthLayout>} />
    <Route path="/login" element={<AuthLayout><LoginPage /></AuthLayout>} />
    <Route path="/admin" element={<AdminRoute />} />
    <Route path="/importer/shipments/:shipmentId" element={<WorkspaceRoute role="IMPORTER" />} />
    <Route path="/importer" element={<WorkspaceRoute role="IMPORTER" />} />
    <Route path="/freight-forwarder/import/:jobId" element={<WorkspaceRoute role="FREIGHT_FORWARDER" page="import" />} />
    <Route path="/freight-forwarder/import" element={<WorkspaceRoute role="FREIGHT_FORWARDER" page="import" />} />
    <Route path="/freight-forwarder/shipments/:shipmentId" element={<WorkspaceRoute role="FREIGHT_FORWARDER" />} />
    <Route path="/freight-forwarder" element={<WorkspaceRoute role="FREIGHT_FORWARDER" />} />
    <Route path="*" element={<HomeRedirect />} />
  </Routes>;
}

export function App() {
  return <BrowserRouter><AppRoutes /></BrowserRouter>;
}
