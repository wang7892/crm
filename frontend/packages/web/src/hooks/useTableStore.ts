import { cloneDeep } from 'lodash-es';

import { SpecialColumnEnum, TableKeyEnum } from '@lib/shared/enums/tableEnum';
import { useI18n } from '@lib/shared/hooks/useI18n';
import { isArraysEqualWithOrder } from '@lib/shared/method/equal';

import type { CrmDataTableColumn, TableStorageConfigItem } from '@/components/pure/crm-table/type';

import useAppStore from '@/store/modules/app';

import useLocalForage from './useLocalForage';

const { t } = useI18n();

export default function useTableStore() {
  const { getItem, setItem } = useLocalForage();
  const appStore = useAppStore();

  async function getTableColumnsMap(tableKey: TableKeyEnum): Promise<TableStorageConfigItem | null> {
    const tableColumnsMap = await getItem<TableStorageConfigItem>(tableKey);
    return tableColumnsMap;
  }

  async function setTableColumnsMap(tableKey: TableKeyEnum, tableColumnsMap: TableStorageConfigItem) {
    await setItem(tableKey, tableColumnsMap);
  }

  function columnsTransform(columns: CrmDataTableColumn[]) {
    columns.forEach((item) => {
      if (item.showInTable === undefined) {
        // 默认在表格中展示
        item.showInTable = true;
      }
      if (item.key === SpecialColumnEnum.OPERATION) {
        item.title = t('common.operation');
      }
    });
    return columns;
  }

  /**
   * 在保留用户原有列顺序的前提下，把代码里新增的列按「代码定义顺序」插入到相邻列之间，
   * 避免整列被追加到表格末尾（例如新增「企微id」应紧跟「邮箱授权码」，而非出现在「更新人」后）。
   */
  function insertNewKeysByCodeOrder(
    oldOrderedKeys: (string | number)[],
    codeBodyCols: CrmDataTableColumn[]
  ): (string | number)[] {
    const codeKeys = codeBodyCols.map((c) => c.key).filter((k) => k != null) as (string | number)[];
    const codeKeySet = new Set(codeKeys);
    const result = oldOrderedKeys.filter((k) => codeKeySet.has(k));

    for (let i = 0; i < codeKeys.length; i++) {
      const k = codeKeys[i];
      if (!result.includes(k)) {
        let insertAt = 0;
        let anchored = false;
        for (let j = i - 1; j >= 0; j--) {
          const prev = codeKeys[j];
          const idx = result.indexOf(prev);
          if (idx >= 0) {
            insertAt = idx + 1;
            anchored = true;
            break;
          }
        }
        if (!anchored) {
          insertAt = 0;
        }
        result.splice(insertAt, 0, k);
      }
    }
    // 新增列若早已写入缓存但排在末尾，上面的插入分支不会执行；将「企微id」固定到「邮箱授权码」之后
    const emailAuthCodeKey = 'emailAuthCode';
    const wecomIdKey = 'wecomId';
    if (result.includes(emailAuthCodeKey) && result.includes(wecomIdKey)) {
      const withoutWecom = result.filter((x) => x !== wecomIdKey);
      const emailIdx = withoutWecom.indexOf(emailAuthCodeKey);
      if (emailIdx >= 0) {
        withoutWecom.splice(emailIdx + 1, 0, wecomIdKey);
        return withoutWecom;
      }
    }
    return result;
  }

  function sortByOldOrder(oldArr: CrmDataTableColumn[], newArr: CrmDataTableColumn[]): CrmDataTableColumn[] {
    const mapNew = new Map(newArr.map((item) => [item.key, item]));

    const isBodyColumnKey = (key: string | number | undefined, col: CrmDataTableColumn | undefined) =>
      !!col &&
      !!key &&
      col.key !== SpecialColumnEnum.OPERATION &&
      col.key !== SpecialColumnEnum.DRAG &&
      col.type !== SpecialColumnEnum.SELECTION &&
      col.columnSelectorDisabled !== true &&
      newArr.some((n) => n.key === key);

    const oldBodyKeys = oldArr.map((item) => item.key).filter((key) => isBodyColumnKey(key, mapNew.get(key))) as (
      | string
      | number
    )[];

    const codeBodyCols = newArr.filter((item) => isBodyColumnKey(item.key, item));

    const mergedBodyKeys = insertNewKeysByCodeOrder(oldBodyKeys, codeBodyCols);
    const sorted = mergedBodyKeys.map((key) => mapNew.get(key)).filter(Boolean) as CrmDataTableColumn[];

    const operationColumn = oldArr.find((item) => item.key === SpecialColumnEnum.OPERATION);
    const selectionColumn = newArr.find((item) => item.type === SpecialColumnEnum.SELECTION);
    const orderColumn = newArr.find((item) => item.key === SpecialColumnEnum.ORDER);
    const dragColumn = newArr.find((item) => item.key === SpecialColumnEnum.DRAG);
    const selectorDisabledColumns = newArr.filter((item) => item.columnSelectorDisabled === true);
    // 将 columnSelectorDisabled 的列放在最前面
    if (selectorDisabledColumns.length) {
      sorted.splice(0, 0, ...selectorDisabledColumns);
    }
    if (orderColumn && selectionColumn) {
      // 如果有排序列和选择列，则将选择列插入到排序列之前
      sorted.splice(0, 0, selectionColumn);
    } else if (selectionColumn) {
      // 如果只有选择列，则将其放在最前面
      sorted.unshift(selectionColumn);
    }
    return [dragColumn, ...sorted, operationColumn].filter(Boolean) as CrmDataTableColumn[];
  }

  function buildMergedColumns(
    storedColumn: CrmDataTableColumn[],
    codeColumn: CrmDataTableColumn[]
  ): CrmDataTableColumn[] {
    return sortByOldOrder(storedColumn, codeColumn).map((e) => {
      const sameItem = storedColumn.find((item) => item.key === e.key);
      if (sameItem) {
        let { width } = sameItem;
        if (e.key === SpecialColumnEnum.OPERATION) {
          const operationColumn = codeColumn.find((item) => item.key === SpecialColumnEnum.OPERATION);
          width = operationColumn?.width;
        } else if (e.key === SpecialColumnEnum.ORDER) {
          const orderColumn = codeColumn.find((item) => item.key === SpecialColumnEnum.ORDER);
          width = orderColumn?.width;
        }
        return {
          ...e,
          width,
          showInTable: sameItem.showInTable,
          fixed: sameItem.fixed || e.fixed,
        };
      }
      return e;
    });
  }

  async function initColumn(tableKey: TableKeyEnum, column: CrmDataTableColumn[]) {
    try {
      const tableColumnsMap = await getTableColumnsMap(tableKey);
      if (!tableColumnsMap) {
        // 如果没有在indexDB里初始化
        column = columnsTransform(column);
        setTableColumnsMap(tableKey, {
          column,
          columnBackup: cloneDeep(column),
        });
      } else {
        // 初始化过了，但是可能有新变动，如列的顺序，列的显示隐藏，列的拖拽
        column = columnsTransform(column);
        const { columnBackup: oldColumn } = tableColumnsMap;
        // 比较页面上定义的 column 和 浏览器备份的column 是否相同
        const isEqual = isArraysEqualWithOrder(oldColumn, column);
        if (!isEqual) {
          // 如果不相等，说明有变动将新的column存入indexDB
          const newColumns = buildMergedColumns(tableColumnsMap.column, column);
          await setTableColumnsMap(tableKey, {
            ...tableColumnsMap,
            column: newColumns,
            columnBackup: cloneDeep(column),
          });
        }
        // 兼容：备份与代码列被误判为「相同」时，IndexedDB 里仍缺少新列 key（如新增字段列），需再合并一次
        const latestMap = await getTableColumnsMap(tableKey);
        if (latestMap) {
          const savedKeys = new Set(latestMap.column.map((c) => c.key).filter(Boolean));
          const missingCodeColumn = column.some(
            (c) =>
              c.key &&
              c.key !== SpecialColumnEnum.OPERATION &&
              c.type !== SpecialColumnEnum.SELECTION &&
              c.key !== SpecialColumnEnum.DRAG &&
              !savedKeys.has(c.key)
          );
          if (missingCodeColumn) {
            const newColumns = buildMergedColumns(latestMap.column, column);
            await setTableColumnsMap(tableKey, {
              ...latestMap,
              column: newColumns,
              columnBackup: cloneDeep(column),
            });
          }
          // 合并规则升级后（如新增列按代码顺序插入而非追加到末尾），纠正本地已缓存的错误列顺序
          const syncedMap = await getTableColumnsMap(tableKey);
          if (syncedMap) {
            const reordered = buildMergedColumns(syncedMap.column, column);
            const oldOrder = syncedMap.column.map((c) => String(c.key)).join(',');
            const newOrder = reordered.map((c) => String(c.key)).join(',');
            if (oldOrder !== newOrder) {
              await setTableColumnsMap(tableKey, {
                ...syncedMap,
                column: reordered,
                columnBackup: cloneDeep(column),
              });
            }
          }
        }
      }
    } catch (e) {
      // eslint-disable-next-line no-console
      console.log(e);
    }
  }

  // 表头显示设置的列
  async function getCanSetColumns(tableKey: TableKeyEnum) {
    const tableColumnsMap = await getTableColumnsMap(tableKey);
    if (tableColumnsMap) {
      return tableColumnsMap.column.filter(
        (item) =>
          item.type !== SpecialColumnEnum.SELECTION &&
          item.key !== SpecialColumnEnum.ORDER &&
          item.key !== SpecialColumnEnum.DRAG
      );
    }
    return [];
  }

  // 在表格上展示的列
  async function getShowInTableColumns(tableKey: TableKeyEnum) {
    const tableColumnsMap = await getTableColumnsMap(tableKey);
    if (tableColumnsMap) {
      return tableColumnsMap.column.filter((i) => i.showInTable);
    }
    return [];
  }

  async function setColumns(tableKey: TableKeyEnum, columns: CrmDataTableColumn[]) {
    try {
      const tableColumnsMap = await getTableColumnsMap(tableKey);
      if (tableColumnsMap) {
        const operationColumn = tableColumnsMap.column.find((i) => i.key === SpecialColumnEnum.OPERATION);
        const newOperationColumn = columns.find((i) => i.key === SpecialColumnEnum.OPERATION);
        const selectColumn = tableColumnsMap.column.find((i) => i.type === SpecialColumnEnum.SELECTION);
        const orderColumn = tableColumnsMap.column.find((i) => i.key === SpecialColumnEnum.ORDER);
        const dragColumn = tableColumnsMap.column.find((i) => i.key === SpecialColumnEnum.DRAG);
        columns = columns.filter((col) => col.key !== SpecialColumnEnum.OPERATION);
        if (orderColumn) columns.unshift(orderColumn);
        if (selectColumn) columns.unshift(selectColumn);
        if (dragColumn) columns.unshift(dragColumn);
        if (operationColumn) {
          columns.push({
            ...operationColumn,
            fixed: newOperationColumn?.fixed,
          });
        }

        tableColumnsMap.column = cloneDeep(columns);
        await setTableColumnsMap(tableKey, tableColumnsMap);
      }
    } catch (e) {
      // eslint-disable-next-line no-console
      console.error('tableStore.setColumns', e);
    }
  }

  async function getPageSize(tableKey: TableKeyEnum) {
    const tableColumnsMap = await getTableColumnsMap(tableKey);
    return tableColumnsMap ? tableColumnsMap.pageSize : appStore.pageSize;
  }

  async function setPageSize(tableKey: TableKeyEnum, pageSize: number): Promise<void> {
    try {
      const tableColumnsMap = await getTableColumnsMap(tableKey);
      if (tableColumnsMap) {
        tableColumnsMap.pageSize = pageSize;
        await setTableColumnsMap(tableKey, tableColumnsMap);
      }
    } catch (e) {
      // eslint-disable-next-line no-console
      console.log(e);
    }
  }

  async function setTableLineHeight(tableKey: TableKeyEnum, layout: string): Promise<void> {
    try {
      const tableColumnsMap = await getTableColumnsMap(tableKey);
      if (tableColumnsMap) {
        tableColumnsMap.layout = layout;
        await setTableColumnsMap(tableKey, tableColumnsMap);
      }
    } catch (e) {
      // eslint-disable-next-line no-console
      console.log(e);
    }
  }

  async function getTableLineHeight(tableKey: TableKeyEnum) {
    const tableColumnsMap = await getTableColumnsMap(tableKey);
    return tableColumnsMap && tableColumnsMap.layout ? tableColumnsMap.layout : 'compact';
  }

  return {
    initColumn,
    getCanSetColumns,
    setColumns,
    getShowInTableColumns,
    setPageSize,
    getPageSize,
    setTableLineHeight,
    getTableLineHeight,
  };
}
