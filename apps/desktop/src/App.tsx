import React from 'react';
import { Monitor, HardDrive, ShieldCheck } from 'lucide-react';

export const App: React.FC = () => {
  return (
    <div style={{ padding: '3rem 2rem', maxWidth: '800px', margin: '0 auto', textAlign: 'center' }}>
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '0.75rem', marginBottom: '1rem' }}>
        <Monitor size={36} color="#4f46e5" />
        <h1 style={{ fontSize: '2.5rem', fontWeight: 700, margin: 0 }}>Vellune Desktop</h1>
      </div>
      <p style={{ fontSize: '1.2rem', color: '#666', fontStyle: 'italic', marginBottom: '2.5rem' }}>
        Local-first knowledge workspace powered by Tauri.
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '1.5rem', textAlign: 'left' }}>
        <div style={{ border: '1px solid #e5e7eb', borderRadius: '12px', padding: '1.5rem' }}>
          <HardDrive size={24} color="#4f46e5" style={{ marginBottom: '0.5rem' }} />
          <h3 style={{ margin: '0 0 0.5rem 0' }}>Local SQLite Storage</h3>
          <p style={{ margin: 0, fontSize: '0.9rem', color: '#777' }}>Instant local access and offline capability.</p>
        </div>
        <div style={{ border: '1px solid #e5e7eb', borderRadius: '12px', padding: '1.5rem' }}>
          <ShieldCheck size={24} color="#4f46e5" style={{ marginBottom: '0.5rem' }} />
          <h3 style={{ margin: '0 0 0.5rem 0' }}>Data Sovereignty</h3>
          <p style={{ margin: 0, fontSize: '0.9rem', color: '#777' }}>Native filesystem markdown vaults & portable notes.</p>
        </div>
      </div>
    </div>
  );
};
