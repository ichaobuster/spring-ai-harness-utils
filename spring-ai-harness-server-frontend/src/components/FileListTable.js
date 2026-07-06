import React, { useState } from 'react';
import { Table, Button, Space, Tag, Popconfirm, Dropdown } from 'antd';
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

const getFileIcon = (item) => {
  if (item.isDirectory) {
    return <FolderFilled style={{ color: '#f59e0b', fontSize: 18 }} />;
  }
  const ext = item.path.split('.').pop().toLowerCase();
  if (['js', 'py', 'java', 'json', 'html', 'css', 'ts', 'sh'].includes(ext)) {
    return <CodeOutlined style={{ color: '#38bdf8', fontSize: 18 }} />;
  }
  if (['md', 'txt', 'log'].includes(ext)) {
    return <FileTextOutlined style={{ color: '#a78bfa', fontSize: 18 }} />;
  }
  return <FileOutlined style={{ color: '#94a3b8', fontSize: 18 }} />;
};

const formatSize = (bytes) => {
  if (bytes === 0) return '-';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
};

export const FileListTable = ({
  items,
  loading,
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

  const columns = [
    {
      title: 'Name',
      dataIndex: 'path',
      key: 'name',
      sorter: (a, b) => a.path.localeCompare(b.path),
      render: (_, record) => {
        const displayName = record.path.split('/').pop() || record.path;
        return (
          <Space
            style={{ cursor: 'pointer', userSelect: 'none' }}
            onClick={() => onItemClick(record)}
          >
            {getFileIcon(record)}
            <span style={{ fontWeight: record.isDirectory ? 600 : 400, color: '#f8fafc' }}>
              {displayName}
            </span>
          </Space>
        );
      }
    },
    {
      title: 'Type',
      dataIndex: 'isDirectory',
      key: 'type',
      width: 120,
      render: (isDir, record) => (
        <Tag color={isDir ? 'warning' : 'processing'}>
          {isDir ? 'Folder' : (record.path.split('.').pop().toUpperCase() || 'File')}
        </Tag>
      )
    },
    {
      title: 'Size',
      dataIndex: 'size',
      key: 'size',
      width: 120,
      sorter: (a, b) => a.size - b.size,
      render: (size, record) => formatSize(record.isDirectory ? 0 : size)
    },
    {
      title: 'Last Modified',
      dataIndex: 'lastModified',
      key: 'lastModified',
      width: 180,
      sorter: (a, b) => a.lastModified - b.lastModified,
      render: (ts) => (ts ? new Date(ts).toLocaleString() : '-')
    },
    {
      title: 'Actions',
      key: 'actions',
      width: 140,
      render: (_, record) => {
        const menuItems = [
          {
            key: 'rename',
            label: 'Rename / Move',
            icon: <EditOutlined />,
            onClick: () => onRename(record)
          },
          ...(!record.isDirectory
            ? [
                {
                  key: 'snapshots',
                  label: 'History Snapshots',
                  icon: <HistoryOutlined />,
                  onClick: () => onOpenSnapshots(record.path)
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
            onClick: () => onDelete(record.path, true)
          }
        ];

        return (
          <Space>
            <Button
              type="text"
              size="small"
              icon={<EditOutlined style={{ color: '#38bdf8' }} />}
              onClick={() => onRename(record)}
            />
            <Popconfirm
              title="Move to Trash?"
              onConfirm={() => onDelete(record.path, true)}
              okText="Trash"
              cancelText="Cancel"
            >
              <Button
                type="text"
                size="small"
                danger
                icon={<DeleteOutlined />}
              />
            </Popconfirm>
            <Dropdown menu={{ items: menuItems }} trigger={['click']}>
              <Button type="text" size="small" icon={<MoreOutlined />} />
            </Dropdown>
          </Space>
        );
      }
    }
  ];

  return (
    <Table
      dataSource={items}
      columns={columns}
      rowKey="path"
      loading={loading}
      pagination={{ pageSize: 12 }}
      onRow={(record) => ({
        draggable: true,
        onDragStart: (e) => handleDragStart(e, record),
        onDragOver: (e) => handleDragOver(e, record),
        onDragLeave: handleDragLeave,
        onDrop: (e) => handleDrop(e, record),
        onDoubleClick: () => onItemClick(record),
        className: `explorer-item ${dragOverPath === record.path ? 'drag-over' : ''}`
      })}
    />
  );
};
