import React, { useState } from 'react';
import { ConfigProvider, theme, Layout, Button } from 'antd';
import { FileExplorer } from './components/FileExplorer';
import { McpDebugger } from './components/McpDebugger';
import { FolderOpenOutlined, BugOutlined } from '@ant-design/icons';

const { Content } = Layout;

function App() {
  const [activeTab, setActiveTab] = useState('files');

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
      <Layout style={{ minHeight: '100vh', background: '#0b0f19' }}>
        {/* Sticky Global Navigation Bar */}
        <div style={{
          background: 'rgba(15, 23, 42, 0.95)',
          backdropFilter: 'blur(12px)',
          borderBottom: '1px solid rgba(255, 255, 255, 0.08)',
          padding: '12px 24px',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          position: 'sticky',
          top: 0,
          zIndex: 1000
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 32 }}>
            <span style={{ fontSize: 16, fontWeight: 700, color: '#f8fafc', letterSpacing: '0.5px' }}>
              Spring AI Harness Console
            </span>
            <div style={{ display: 'flex', gap: 8 }}>
              <Button
                type={activeTab === 'files' ? 'primary' : 'text'}
                icon={<FolderOpenOutlined />}
                onClick={() => setActiveTab('files')}
                style={{ fontWeight: 600, borderRadius: 6 }}
              >
                File Manager
              </Button>
              <Button
                type={activeTab === 'mcp' ? 'primary' : 'text'}
                icon={<BugOutlined />}
                onClick={() => setActiveTab('mcp')}
                style={{ fontWeight: 600, borderRadius: 6 }}
              >
                MCP Client Debugger
              </Button>
            </div>
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
