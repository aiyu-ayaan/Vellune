import React from 'react';
import { BookOpen, Sparkles, Feather } from 'lucide-react';

export const App: React.FC = () => {
  return (
    <div style={{ padding: '4rem 2rem', maxWidth: '800px', margin: '0 auto', textAlign: 'center' }}>
      <header>
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '0.75rem', marginBottom: '1rem' }}>
          <Feather size={36} color="#4f46e5" />
          <h1 style={{ fontSize: '2.5rem', fontWeight: 700, margin: 0 }}>Vellune</h1>
        </div>
        <p style={{ fontSize: '1.25rem', color: '#666', fontStyle: 'italic', marginBottom: '2rem' }}>
          Where thoughts take form.
        </p>
      </header>

      <main style={{ marginTop: '3rem', display: 'flex', gap: '1.5rem', justifyContent: 'center', flexWrap: 'wrap' }}>
        <div style={{ border: '1px solid #e5e7eb', borderRadius: '12px', padding: '1.5rem', width: '220px', textAlign: 'left' }}>
          <BookOpen size={24} color="#4f46e5" style={{ marginBottom: '0.5rem' }} />
          <h3 style={{ margin: '0 0 0.5rem 0' }}>Local-First Notes</h3>
          <p style={{ margin: 0, fontSize: '0.9rem', color: '#777' }}>Markdown, wikilinks, and offline storage.</p>
        </div>
        <div style={{ border: '1px solid #e5e7eb', borderRadius: '12px', padding: '1.5rem', width: '220px', textAlign: 'left' }}>
          <Sparkles size={24} color="#4f46e5" style={{ marginBottom: '0.5rem' }} />
          <h3 style={{ margin: '0 0 0.5rem 0' }}>Infinite Canvas</h3>
          <p style={{ margin: 0, fontSize: '0.9rem', color: '#777' }}>Spatial whiteboards, cards, and vector drawings.</p>
        </div>
      </main>
    </div>
  );
};
