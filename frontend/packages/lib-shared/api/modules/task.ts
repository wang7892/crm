import type { CordysAxios } from '@lib/shared/api/http/Axios';
import {
  AddTaskUrl,
  DeleteTaskAttachmentUrl,
  DeleteTaskUrl,
  DownloadTaskAttachmentUrl,
  GetTaskAssigneeOptionsUrl,
  GetTaskCustomerOptionsUrl,
  GetTaskPageUrl,
  GetTaskUrl,
  PreviewTaskAttachmentUrl,
  ReassignTaskUrl,
  RegenerateTaskAiReplyUrl,
  SaveTaskReportUrl,
  StartTaskUrl,
  SubmitTaskReportUrl,
  UpdateTaskUrl,
  UploadTaskAttachmentUrl,
} from '@lib/shared/api/requrls/task';
import type { CommonList } from '@lib/shared/models/common';
import type {
  TaskAttachmentItem,
  TaskAttachmentScene,
  TaskItem,
  TaskOptionItem,
  TaskPageParams,
  TaskReassignParams,
  TaskReportSaveParams,
  TaskSaveParams,
  TaskUpdateParams,
} from '@lib/shared/models/task';

export default function useTaskApi(CDR: CordysAxios) {
  function getTaskPage(data: TaskPageParams) {
    return CDR.post<CommonList<TaskItem>>({ url: GetTaskPageUrl, data });
  }

  function getTask(id: string) {
    return CDR.get<TaskItem>({ url: `${GetTaskUrl}/${id}` });
  }

  function addTask(data: TaskSaveParams) {
    return CDR.post<TaskItem>({ url: AddTaskUrl, data });
  }

  function updateTask(data: TaskUpdateParams) {
    return CDR.post<TaskItem>({ url: UpdateTaskUrl, data });
  }

  function reassignTask(data: TaskReassignParams) {
    return CDR.post<TaskItem>({ url: ReassignTaskUrl, data });
  }

  function deleteTask(id: string) {
    return CDR.get({ url: `${DeleteTaskUrl}/${id}` });
  }

  function startTask(id: string) {
    return CDR.post<TaskItem>({ url: `${StartTaskUrl}/${id}` });
  }

  function saveTaskReport(data: TaskReportSaveParams) {
    return CDR.post<TaskItem>({ url: SaveTaskReportUrl, data });
  }

  function submitTaskReport(data: TaskReportSaveParams) {
    return CDR.post<TaskItem>({ url: SubmitTaskReportUrl, data });
  }

  function regenerateTaskAiReply(id: string) {
    return CDR.post<TaskItem>({ url: `${RegenerateTaskAiReplyUrl}/${id}` });
  }

  function getTaskAssigneeOptions() {
    return CDR.get<TaskOptionItem[]>({ url: GetTaskAssigneeOptionsUrl });
  }

  function getTaskCustomerOptions(keyword = '') {
    return CDR.get<TaskOptionItem[]>({ url: GetTaskCustomerOptionsUrl, params: { keyword } });
  }

  function uploadTaskAttachments(taskId: string, scene: TaskAttachmentScene, files: File[]) {
    return CDR.uploadFile<TaskAttachmentItem[]>(
      { url: `${UploadTaskAttachmentUrl}/${taskId}/${scene}` },
      { fileList: files },
      'files'
    );
  }

  function deleteTaskAttachment(id: string) {
    return CDR.delete({ url: `${DeleteTaskAttachmentUrl}/${id}` });
  }

  function downloadTaskAttachment(id: string) {
    return CDR.get({ url: `${DownloadTaskAttachmentUrl}/${id}`, responseType: 'blob' }, { isTransformResponse: false });
  }

  function previewTaskAttachment(id: string) {
    return CDR.get({ url: `${PreviewTaskAttachmentUrl}/${id}`, responseType: 'blob' }, { isTransformResponse: false });
  }

  return {
    getTaskPage,
    getTask,
    addTask,
    updateTask,
    reassignTask,
    deleteTask,
    startTask,
    saveTaskReport,
    submitTaskReport,
    regenerateTaskAiReply,
    getTaskAssigneeOptions,
    getTaskCustomerOptions,
    uploadTaskAttachments,
    deleteTaskAttachment,
    downloadTaskAttachment,
    previewTaskAttachment,
  };
}
