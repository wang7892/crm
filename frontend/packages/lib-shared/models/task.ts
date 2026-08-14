import type { TableQueryParams } from './common';

export type TaskStatus = 'PENDING' | 'IN_PROGRESS' | 'OVERDUE' | 'COMPLETED';
export type TaskSource = 'AI' | 'MANAGER';
export type TaskReportState = 'UNSAVED' | 'DRAFT' | 'SUBMITTED';
export type TaskAttachmentScene = 'TASK' | 'REPORT';

export interface TaskAttachmentItem {
  id: string;
  taskId: string;
  scene: TaskAttachmentScene;
  originalName: string;
  contentType?: string;
  sizeBytes: number;
  createTime: number;
}

export interface TaskItem {
  id: string;
  name: string;
  source: TaskSource;
  assigneeId?: string;
  assigneeName?: string;
  customerId?: string;
  customerName?: string;
  description?: string;
  deadline: number;
  status: TaskStatus;
  reportContent?: string;
  aiReply?: string;
  startedAt?: number;
  completedAt?: number;
  reportSubmittedAt?: number;
  createTime: number;
  updateTime: number;
  reportState: TaskReportState;
  taskAttachments: TaskAttachmentItem[];
  reportAttachments: TaskAttachmentItem[];
}

export interface TaskPageParams extends TableQueryParams {
  status?: TaskStatus;
  assigneeId?: string;
}

export interface TaskSaveParams {
  name: string;
  assigneeId: string;
  customerId?: string;
  description?: string;
  deadline: number;
}

export interface TaskUpdateParams extends TaskSaveParams {
  id: string;
}

export interface TaskReassignParams {
  id: string;
  assigneeId: string;
}

export interface TaskReportSaveParams {
  id: string;
  reportContent?: string;
  aiReply?: string;
}

export interface TaskOptionItem {
  id: string;
  name: string;
}
