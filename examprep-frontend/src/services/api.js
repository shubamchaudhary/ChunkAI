import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000, // 30 second timeout
  withCredentials: false, // Important: Set to false for CORS
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor - Add token to requests
api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    // Log request for debugging (remove in production)
    console.log(`[API Request] ${config.method?.toUpperCase()} ${config.baseURL}${config.url}`);
    return config;
  },
  (error) => {
    console.error('[API Request Error]', error);
    return Promise.reject(error);
  }
);

// Response interceptor - Better error handling
api.interceptors.response.use(
  (response) => {
    return response;
  },
  (error) => {
    // Log detailed error information
    if (error.response) {
      // Server responded with error status
      console.error('[API Error Response]', {
        status: error.response.status,
        statusText: error.response.statusText,
        data: error.response.data,
        url: error.config?.url,
        baseURL: error.config?.baseURL,
      });
    } else if (error.request) {
      // Request made but no response received (network error, backend down, etc.)
      console.error('[API Network Error]', {
        message: error.message,
        url: error.config?.url,
        baseURL: error.config?.baseURL,
        code: error.code,
      });
    } else {
      // Error in request setup
      console.error('[API Request Setup Error]', error.message);
    }
    return Promise.reject(error);
  }
);

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

