import { BrowserRouter, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { AuthLayout } from './components/AuthLayout';
import { SignupPage } from './signup/SignupPage';
import { LoginPage } from './login/LoginPage';

function SignupRoute() {
  const navigate = useNavigate();
  return <SignupPage onRegistered={account => navigate('/login', {
    replace: true, state: { registered: true, email: account.email },
  })} />;
}

export function AppRoutes() {
  return <AuthLayout><Routes>
    <Route path="/signup" element={<SignupRoute />} />
    <Route path="/login" element={<LoginPage />} />
    <Route path="*" element={<Navigate to="/signup" replace />} />
  </Routes></AuthLayout>;
}

export function App() {
  return <BrowserRouter><AppRoutes /></BrowserRouter>;
}
