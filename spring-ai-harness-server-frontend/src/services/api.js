import axios from 'axios';

const BASE_URL = '';

export const api = {
  // --- User Workspace APIs ---
  listFiles: async (authHeader, path = '') => {
    const res = await axios.get(`${BASE_URL}/api/v1/workspace/files`, {
      params: { path },
      headers: { Authorization: authHeader }
    });
    return res.data;
  },

  getFileContent: async (authHeader, path) => {
    const res = await axios.get(`${BASE_URL}/api/v1/workspace/files/content`, {
      params: { path },
      headers: { Authorization: authHeader }
    });
    return res.data;
  },

  uploadFile: async (authHeader, path, fileOrContent) => {
    const formData = new FormData();
    if (fileOrContent instanceof File || fileOrContent instanceof Blob) {
      formData.append('file', fileOrContent);
    } else {
      const blob = new Blob([fileOrContent], { type: 'text/plain' });
      formData.append('file', blob, 'file.txt');
    }
    const res = await axios.post(`${BASE_URL}/api/v1/workspace/files/upload`, formData, {
      params: { path },
      headers: {
        Authorization: authHeader,
        'Content-Type': 'multipart/form-data'
      }
    });
    return res.data;
  },

  deleteFile: async (authHeader, path, trash = true) => {
    const res = await axios.delete(`${BASE_URL}/api/v1/workspace/files`, {
      params: { path, trash },
      headers: { Authorization: authHeader }
    });
    return res.data;
  },

  moveFile: async (authHeader, fromPath, toPath) => {
    const res = await axios.post(`${BASE_URL}/api/v1/workspace/files/move`, null, {
      params: { fromPath, toPath },
      headers: { Authorization: authHeader }
    });
    return res.data;
  },

  listSnapshots: async (authHeader, path = '') => {
    const res = await axios.get(`${BASE_URL}/api/v1/workspace/snapshots`, {
      params: { path },
      headers: { Authorization: authHeader }
    });
    return res.data;
  },

  rewind: async (authHeader, snapshotId) => {
    const res = await axios.post(`${BASE_URL}/api/v1/workspace/rewind/${snapshotId}`, null, {
      headers: { Authorization: authHeader }
    });
    return res.data;
  },

  // --- Admin APIs ---
  listWorkspaces: async (adminToken) => {
    const res = await axios.get(`${BASE_URL}/api/v1/admin/workspaces`, {
      headers: { 'X-Admin-Token': adminToken }
    });
    return res.data;
  },

  listAdminWorkspaceFiles: async (adminToken, workspaceKey, path = '') => {
    const res = await axios.get(`${BASE_URL}/api/v1/admin/workspaces/${workspaceKey}/files`, {
      params: { path },
      headers: { 'X-Admin-Token': adminToken }
    });
    return res.data;
  },

  deleteAdminWorkspaceFile: async (adminToken, workspaceKey, path) => {
    const res = await axios.delete(`${BASE_URL}/api/v1/admin/workspaces/${workspaceKey}/files`, {
      params: { path },
      headers: { 'X-Admin-Token': adminToken }
    });
    return res.data;
  },

  moveAdminWorkspaceFile: async (adminToken, workspaceKey, fromPath, toPath) => {
    const res = await axios.post(`${BASE_URL}/api/v1/admin/workspaces/${workspaceKey}/files/move`, null, {
      params: { fromPath, toPath },
      headers: { 'X-Admin-Token': adminToken }
    });
    return res.data;
  },

  callMcp: async (authHeader, payload) => {
    const res = await axios.post(`${BASE_URL}/mcp`, payload, {
      headers: {
        Authorization: authHeader,
        'Content-Type': 'application/json'
      }
    });
    return res.data;
  }
};
