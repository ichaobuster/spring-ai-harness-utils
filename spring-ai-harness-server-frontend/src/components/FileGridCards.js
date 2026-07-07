import React, { useState } from 'react';
import { Row, Col, Card, Typography, Dropdown } from 'antd';
import {
  FolderFilled,
  FileTextOutlined,
  CodeOutlined,
  FileOutlined,
  DeleteOutlined,
  EditOutlined,
  HistoryOutlined,
  MoreOutlined
} from '@ant-design/icons';

const { Text } = Typography;

const getFileIcon = (item) => {
  if (item.isDirectory) {
    return <FolderFilled style={{ color: '#f59e0b', fontSize: 42 }} />;
  }
  const ext = item.path.split('.').pop().toLowerCase();
  if (['js', 'py', 'java', 'json', 'html', 'css', 'ts', 'sh'].includes(ext)) {
    return <CodeOutlined style={{ color: '#38bdf8', fontSize: 42 }} />;
  }
  if (['md', 'txt', 'log'].includes(ext)) {
    return <FileTextOutlined style={{ color: '#a78bfa', fontSize: 42 }} />;
  }
  return <FileOutlined style={{ color: '#94a3b8', fontSize: 42 }} />;
};

const formatSize = (bytes) => {
  if (bytes === 0) return '-';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
};

export const FileGridCards = ({
  items,
  onItemClick,
  onRename,
  onDelete,
  onOpenSnapshots,
  onMoveItem
}) => {
  const [draggedItem, setDraggedItem] = useState(null);
  const [dragOverPath, setDragOverPath] = useState(null);

  const handleDragStart = (e, item) => {
    setDraggedItem(item);
    e.dataTransfer.setData('text/plain', item.path);
  };

  const handleDragOver = (e, item) => {
    if (item.isDirectory && draggedItem && draggedItem.path !== item.path) {
      e.preventDefault();
      setDragOverPath(item.path);
    }
  };

  const handleDragLeave = (e) => {
    setDragOverPath(null);
  };

  const handleDrop = (e, targetFolder) => {
    e.preventDefault();
    setDragOverPath(null);
    if (draggedItem && targetFolder.isDirectory && draggedItem.path !== targetFolder.path) {
      const fileName = draggedItem.path.split('/').pop();
      const newPath = `${targetFolder.path.replace(/\/$/, '')}/${fileName}`;
      onMoveItem(draggedItem.path, newPath);
    }
    setDraggedItem(null);
  };

  return (
    <Row gutter={[16, 16]} style={{ padding: '16px 0' }}>
      {items.map((item) => {
        const displayName = item.path.split('/').pop() || item.path;

        const menuItems = [
          {
            key: 'rename',
            label: 'Rename / Move',
            icon: <EditOutlined />,
            onClick: () => onRename(item)
          },
          ...(!item.isDirectory
            ? [
                {
                  key: 'snapshots',
                  label: 'History Snapshots',
                  icon: <HistoryOutlined />,
                  onClick: () => onOpenSnapshots(item.path)
                }
              ]
            : []),
          {
            type: 'divider'
          },
          {
            key: 'delete',
            label: 'Move to Trash',
            icon: <DeleteOutlined />,
            danger: true,
            onClick: () => onDelete(item.path, true)
          }
        ];

        return (
          <Col xs={12} sm={8} md={6} lg={4} key={item.path}>
            <Card
              hoverable
              draggable
              onDragStart={(e) => handleDragStart(e, item)}
              onDragOver={(e) => handleDragOver(e, item)}
              onDragLeave={handleDragLeave}
              onDrop={(e) => handleDrop(e, item)}
              onDoubleClick={() => onItemClick(item)}
              className={`explorer-item ${dragOverPath === item.path ? 'drag-over' : ''}`}
              style={{
                textAlign: 'center',
                background: 'rgba(30, 41, 59, 0.6)',
                borderColor: 'rgba(255, 255, 255, 0.08)'
              }}
              bodyStyle={{ padding: 16 }}
            >
              <div style={{ position: 'absolute', top: 8, right: 8 }}>
                <Dropdown menu={{ items: menuItems }} trigger={['click']}>
                  <MoreOutlined style={{ color: '#94a3b8', fontSize: 16, cursor: 'pointer' }} />
                </Dropdown>
              </div>

              <div style={{ marginBottom: 12 }}>{getFileIcon(item)}</div>

              <Text
                ellipsis={{ tooltip: displayName }}
                style={{
                  color: '#f8fafc',
                  fontWeight: item.isDirectory ? 600 : 400,
                  display: 'block',
                  marginBottom: 4
                }}
              >
                {displayName}
              </Text>

              <Text type="secondary" style={{ fontSize: 12 }}>
                {item.isDirectory ? 'Folder' : formatSize(item.size)}
              </Text>
            </Card>
          </Col>
        );
      })}
    </Row>
  );
};
