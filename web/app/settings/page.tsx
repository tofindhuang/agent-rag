import { AuthGate } from "@/components/auth/AuthGate";
import { SettingsPage } from "@/components/settings/SettingsPage";

export default function Page() {
  return (
    <AuthGate>
      <SettingsPage />
    </AuthGate>
  );
}
