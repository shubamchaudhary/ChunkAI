import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

const api = axios.create({
  baseURL: API_BASE_URL,
});

// Add token to requests
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Auth API
export const authAPI = {
  register: (data) => api.post('/auth/register', data),
  login: (data) => api.post('/auth/login', data),
};

// Chat API
export const chatAPI = {
  create: (data) => api.post('/chats', data),
  getAll: (params) => api.get('/chats', { params }),
  getById: (id) => api.get(`/chats/${id}`),
  update: (id, data) => api.put(`/chats/${id}`, data),
  delete: (id) => api.delete(`/chats/${id}`),
};

// Document API
export const documentAPI = {
  upload: (file, chatId) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('chatId', chatId);
    return api.post('/documents/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  uploadBulk: (files, chatId) => {
    const formData = new FormData();
    files.forEach(file => formData.append('files', file));
    formData.append('chatId', chatId);
    return api.post('/documents/upload/bulk', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  getAll: (params) => api.get('/documents', { params }),
  getById: (id) => api.get(`/documents/${id}`),
  delete: (id, chatId) => api.delete(`/documents/${id}`, { params: { chatId } }),
  getStatus: (id) => api.get(`/documents/${id}/status`),
};

// Query API
export const queryAPI = {
  query: (data) => api.post('/query', data),
  getHistory: (params) => api.get('/query/history', { params }),
};

export default api;

