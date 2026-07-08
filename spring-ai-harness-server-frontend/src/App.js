import React, { useState, useEffect } from 'react';
import { ConfigProvider, theme, Layout, Button } from 'antd';
import { FileExplorer } from './components/FileExplorer';
import { McpDebugger } from './components/McpDebugger';
import { FolderOpenOutlined, BugOutlined, SunOutlined, MoonOutlined } from '@ant-design/icons';

const { Content } = Layout;

function App() {
  const [activeTab, setActiveTab] = useState('files');
  const [themeMode, setThemeMode] = useState(() => {
    return localStorage.getItem('theme-mode') || 'dark';
  });

  const toggleTheme = () => {
    const nextTheme = themeMode === 'dark' ? 'light' : 'dark';
    setThemeMode(nextTheme);
    localStorage.setItem('theme-mode', nextTheme);
  };

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', themeMode);
  }, [themeMode]);

  return (
    <ConfigProvider
      theme={{
        algorithm: themeMode === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
        token: {
          colorPrimary: '#3b82f6',
          borderRadius: 8,
          colorBgContainer: themeMode === 'dark' ? '#1e293b' : '#ffffff',
          colorBgElevated: themeMode === 'dark' ? '#1e293b' : '#ffffff',
          colorBorder: themeMode === 'dark' ? 'rgba(255, 255, 255, 0.08)' : 'rgba(0, 0, 0, 0.08)'
        }
      }}
    >
      <Layout style={{ minHeight: '100vh', background: 'var(--bg-primary)' }}>
        {/* Sticky Global Navigation Bar */}
        <div style={{
          background: 'var(--bg-navbar)',
          backdropFilter: 'blur(12px)',
          borderBottom: '1px solid var(--border-color)',
          padding: '12px 24px',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          position: 'sticky',
          top: 0,
          zIndex: 1000
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 32 }}>
            <span style={{ fontSize: 16, fontWeight: 700, color: 'var(--text-navbar)', letterSpacing: '0.5px' }}>
              Spring AI Harness Console
            </span>
            <div style={{ display: 'flex', gap: 8 }}>
              <Button
                type={activeTab === 'files' ? 'primary' : 'text'}
                icon={<FolderOpenOutlined />}
                onClick={() => setActiveTab('files')}
                style={{
                  fontWeight: 600,
                  borderRadius: 6,
                  color: activeTab === 'files' ? undefined : 'var(--text-secondary)'
                }}
              >
                File Manager
              </Button>
              <Button
                type={activeTab === 'mcp' ? 'primary' : 'text'}
                icon={<BugOutlined />}
                onClick={() => setActiveTab('mcp')}
                style={{
                  fontWeight: 600,
                  borderRadius: 6,
                  color: activeTab === 'mcp' ? undefined : 'var(--text-secondary)'
                }}
              >
                MCP Client Debugger
              </Button>
            </div>
          </div>
          {/* Theme Toggle Button */}
          <div>
            <Button
              type="text"
              icon={themeMode === 'dark' ? <SunOutlined style={{ color: '#eab308' }} /> : <MoonOutlined style={{ color: '#4f46e5' }} />}
              onClick={toggleTheme}
              style={{ fontSize: 16 }}
            />
          </div>
        </div>

        {/* Active Workspace/Debugger View */}
        <Content style={{ minHeight: 'calc(100vh - 57px)' }}>
          {activeTab === 'files' ? <FileExplorer /> : <McpDebugger />}
        </Content>
      </Layout>
    </ConfigProvider>
  );
}

export default App;
