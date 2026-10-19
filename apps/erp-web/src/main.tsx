import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { ModuleRegistry, AllCommunityModule } from 'ag-grid-community';
import { AuthProvider } from '@/auth/auth';
import { createQueryClient } from '@/app/queryClient';
import { App } from '@/app/App';
import './styles.css';

ModuleRegistry.registerModules([AllCommunityModule]);

const queryClient = createQueryClient();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
