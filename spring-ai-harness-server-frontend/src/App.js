import React from 'react';
import { ConfigProvider, theme } from 'antd';
import { FileExplorer } from './components/FileExplorer';

function App() {
  return (
    <ConfigProvider
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#3b82f6',
          borderRadius: 8,
          colorBgContainer: '#1e293b',
          colorBgElevated: '#1e293b',
          colorBorder: 'rgba(255, 255, 255, 0.08)'
        }
      }}
    >
      <FileExplorer />
    </ConfigProvider>
  );
}

export default App;
