<template>
  <div class="agent-charts">
    <section v-for="(chart, index) in charts" :key="index" class="agent-chart">
      <header>
        <h3>{{ chart.title }}</h3>
        <div class="chart-modes" role="group" aria-label="图表类型">
          <label
            v-for="mode in chartModes"
            :key="mode.value"
            :class="{ active: (modes[index] || chart.type) === mode.value }"
          >
            <input
              type="radio"
              :name="`chart-${groupId}-${index}`"
              :checked="(modes[index] || chart.type) === mode.value"
              @change="modes[index] = mode.value"
            />{{ mode.label }}
          </label>
        </div>
      </header>
      <div :ref="(element) => bindCanvas(index, element)" class="chart-canvas" role="img" :aria-label="chart.title" />
      <p v-if="failedCharts.has(index)" class="chart-error" role="status">图表暂时无法显示。</p>
      <p v-else class="chart-coverage" :class="{ partial: chart.partial }">{{ chart.coverage }}</p>
    </section>
  </div>
</template>

<script setup lang="ts">
  import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

  import type { AiAgentChart } from '@lib/shared/api/modules/aiAgent';
  import { BarChart, LineChart, PieChart } from 'echarts/charts';
  import {
    DataZoomComponent,
    GridComponent,
    LegendComponent,
    ToolboxComponent,
    TooltipComponent,
  } from 'echarts/components';
  import { type EChartsCoreOption, init, use } from 'echarts/core';
  import { CanvasRenderer } from 'echarts/renderers';

  use([
    BarChart,
    LineChart,
    PieChart,
    DataZoomComponent,
    GridComponent,
    LegendComponent,
    ToolboxComponent,
    TooltipComponent,
    CanvasRenderer,
  ]);
  const props = defineProps<{ charts: AiAgentChart[] }>();
  type ChartType = AiAgentChart['type'];
  type ZoomRange = { start?: number; end?: number; startValue?: number; endValue?: number };
  const chartModes: { value: ChartType; label: string }[] = [
    { value: 'column', label: '柱状图' },
    { value: 'bar', label: '条形图' },
    { value: 'line', label: '折线图' },
    { value: 'pie', label: '饼图' },
  ];
  const modes = ref<Record<number, ChartType>>({});
  const failedCharts = ref(new Set<number>());
  const groupId = Math.random().toString(36).slice(2);
  const elements = new Map<number, HTMLDivElement>();
  const instances = new Map<number, ReturnType<typeof init>>();
  let resizeFrame: number | undefined;
  const observer = new ResizeObserver(() => {
    if (resizeFrame !== undefined) return;
    resizeFrame = requestAnimationFrame(() => {
      resizeFrame = undefined;
      instances.forEach((instance, index) => {
        const element = elements.get(index);
        if (!element || !element.clientWidth || !element.clientHeight) return;
        if (element.clientWidth === instance.getWidth() && element.clientHeight === instance.getHeight()) return;
        try {
          const chart = props.charts[index];
          const range = (instance.getOption().dataZoom as ZoomRange[] | undefined)?.[0];
          instance.resize();
          if (chart && (modes.value[index] || chart.type) !== 'pie') {
            instance.setOption(option(chart, modes.value[index] || chart.type, element.clientWidth, range), {
              replaceMerge: ['dataZoom'],
            });
          }
        } catch (error) {
          failChart(index, error);
        }
      });
    });
  });
  function disposeChart(index: number) {
    const instance = instances.get(index);
    if (!instance) return;
    observer.unobserve(instance.getDom());
    instance.dispose();
    instances.delete(index);
  }
  function failChart(index: number, error: unknown) {
    disposeChart(index);
    failedCharts.value.add(index);
    console.warn('AI agent chart rendering failed', error);
  }
  function bindCanvas(index: number, element: unknown) {
    if (element instanceof HTMLDivElement) elements.set(index, element);
    else elements.delete(index);
  }
  function draw() {
    instances.forEach((_instance, index) => {
      if (!elements.has(index)) {
        disposeChart(index);
        failedCharts.value.delete(index);
      }
    });
    props.charts.forEach((chart, index) => {
      const element = elements.get(index);
      if (!element) return;
      try {
        let instance = instances.get(index);
        if (!instance) {
          instance = init(element, undefined, { renderer: 'canvas' });
          instances.set(index, instance);
        }
        instance.setOption(option(chart, modes.value[index] || chart.type, element.clientWidth), { notMerge: true });
        observer.observe(element);
        failedCharts.value.delete(index);
      } catch (error) {
        failChart(index, error);
      }
    });
  }
  onMounted(draw);
  watch([() => props.charts, modes], draw, { deep: true, flush: 'post' });
  onBeforeUnmount(() => {
    observer.disconnect();
    if (resizeFrame !== undefined) cancelAnimationFrame(resizeFrame);
    instances.forEach((instance) => instance.dispose());
    instances.clear();
  });
  function option(chart: AiAgentChart, type: ChartType, width: number, range?: ZoomRange): EChartsCoreOption {
    const common = {
      animationDuration: 250,
      color: ['#3277dc', '#20a187', '#e6a23c', '#df657c'],
      legend: { type: 'scroll', top: 0, right: 40, left: 0 },
      toolbox: { right: 0, feature: { saveAsImage: { title: '下载图表', name: chart.title } } },
    };
    if (type === 'pie')
      return {
        ...common,
        tooltip: { trigger: 'item', renderMode: 'richText', confine: true },
        series: [
          {
            type: 'pie',
            radius: '65%',
            top: 30,
            data: chart.categories.map((name, index) => ({ name, value: chart.series[0]?.values[index] ?? 0 })),
          },
        ],
      };
    const horizontal = type === 'bar';
    // Limit the visible window so every category has room for its own label.
    const capacity = horizontal ? 10 : Math.max(1, Math.min(10, Math.floor((width - 80) / 80)));
    const visibleCount = Math.min(capacity, chart.categories.length);
    const showZoom = chart.categories.length > visibleCount;
    const startValue = Math.max(
      0,
      range?.startValue ?? Math.round(((range?.start ?? 0) / 100) * (chart.categories.length - 1))
    );
    const endValue = Math.min(
      chart.categories.length - 1,
      startValue + visibleCount - 1,
      range?.endValue ?? Math.round(((range?.end ?? 100) / 100) * (chart.categories.length - 1))
    );
    const category = {
      type: 'category',
      name: horizontal ? chart.dimension : '',
      data: chart.categories,
      tooltip: { show: true, renderMode: 'richText', confine: true },
      axisLabel: {
        interval: 0,
        hideOverlap: false,
        showMinLabel: true,
        showMaxLabel: true,
        width: horizontal ? 120 : 70,
        height: horizontal ? 16 : 64,
        lineHeight: 16,
        overflow: horizontal ? 'truncate' : 'breakAll',
        lineOverflow: 'truncate',
      },
    };
    const value = {
      type: 'value',
      splitNumber: horizontal && width < 480 ? 2 : 5,
      axisLabel: {
        hideOverlap: true,
        formatter: (n: number) => Intl.NumberFormat('zh-CN', { notation: 'compact' }).format(n),
      },
    };
    const cutoff = chart.predictionStart;
    const series = chart.series.flatMap((item) => {
      const base = { name: item.name, type: type === 'line' ? 'line' : 'bar', barMaxWidth: 40, connectNulls: false };
      if (type !== 'line' || cutoff == null) return [{ ...base, data: item.values }];
      return [
        { ...base, data: item.values.map((n, i) => (i < cutoff ? n : null)) },
        {
          ...base,
          name: `${item.name}（预测）`,
          lineStyle: { type: 'dashed' },
          data: item.values.map((n, i) => (i >= cutoff - 1 ? n : null)),
        },
      ];
    });
    return {
      ...common,
      tooltip: { trigger: 'axis', renderMode: 'richText', confine: true },
      grid: {
        top: 50,
        left: 12,
        right: horizontal && showZoom ? 44 : 12,
        bottom: !horizontal && showZoom ? 52 : 16,
        containLabel: true,
      },
      xAxis: horizontal ? value : category,
      yAxis: horizontal ? category : value,
      dataZoom: showZoom
        ? [
            {
              type: 'slider',
              showDetail: false,
              brushSelect: false,
              startValue,
              endValue,
              maxValueSpan: visibleCount - 1,
              ...(horizontal
                ? { orient: 'vertical', yAxisIndex: 0, right: 0, top: 50, bottom: 24, width: 18 }
                : { orient: 'horizontal', xAxisIndex: 0, bottom: 8, height: 18 }),
            },
          ]
        : [],
      series,
    };
  }
</script>

<style scoped>
  .agent-charts {
    margin-top: 20px;
    min-width: 0;
  }
  .agent-chart {
    padding: 16px 0;
    min-width: 0;
    border-top: 1px solid var(--text-n8, #e5e6eb);
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }
  h3 {
    margin: 0;
    min-width: 0;
    font-size: 14px;
    font-weight: 500;
    overflow-wrap: anywhere;
  }
  .chart-modes {
    display: flex;
    overflow: hidden;
    border: 1px solid var(--text-n7, #dcdfe6);
    border-radius: 4px;
  }
  .chart-modes label {
    position: relative;
    padding: 4px 10px;
    font-size: 12px;
    white-space: nowrap;
    cursor: pointer;
  }
  .chart-modes label.active {
    color: #2468c5;
    background: #edf4ff;
  }
  .chart-modes label:focus-within {
    outline: 2px solid #3277dc;
    outline-offset: -2px;
  }
  .chart-modes input {
    /* Keep the native focus target inside the visible label to prevent scroll jumps. */
    position: absolute;
    top: 0;
    left: 0;
    margin: 0;
    width: 1px;
    height: 1px;
    opacity: 0;
  }
  .chart-canvas {
    margin-top: 12px;
    width: 100%;
    height: 360px;
  }
  .chart-coverage {
    margin: 8px 0 0;
    font-size: 12px;
    color: var(--text-n3, #68717e);
    overflow-wrap: anywhere;
  }
  .partial {
    color: #a96813;
  }
  .chart-error {
    margin: 8px 0 0;
    font-size: 12px;
    color: #a96813;
  }
</style>
