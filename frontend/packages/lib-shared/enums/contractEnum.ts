export enum ContractPaymentPlanEnum {
  PENDING = 'PENDING', // 未完成
  PARTIALLY_COMPLETED = 'PARTIALLY_COMPLETED', // 部分完成
  COMPLETED = 'COMPLETED', // 已完成
}

export enum ContractBusinessTitleStatusEnum {
  APPROVED = 'APPROVED', // 通过
  UNAPPROVED = 'UNAPPROVED', // 未通过
  APPROVING = 'APPROVING', // 提审中
  REVOKED = 'REVOKED', // 撤销
}

export enum ContractInvoiceStatusEnum {
  APPROVED = 'APPROVED', // 通过
  UNAPPROVED = 'UNAPPROVED', // 未通过
  APPROVING = 'APPROVING', // 提审中
  REVOKED = 'REVOKED', // 撤销
  NONE = 'NONE', // 未开启审批状态
}
