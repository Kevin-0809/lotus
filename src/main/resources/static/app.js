/* 页面级增强与运行参数默认值，业务 API 和渲染函数仍由 index.html 提供。 */
(function () {
  function defaultParallelism() {
    return Math.max(1, Math.min(navigator.hardwareConcurrency || 2, 8));
  }

  window.addEventListener('DOMContentLoaded', function () {
    var input = document.getElementById('parallelism');
    if (input && (!input.value || input.value === '1')) {
      input.value = defaultParallelism();
      input.max = '16';
      input.title = '按表并行执行，建议不超过数据库连接承载能力';
    }
    document.querySelectorAll('.nav-item').forEach(function (item) {
      item.setAttribute('role', 'button');
      item.setAttribute('tabindex', '0');
      item.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' || event.key === ' ') item.click();
      });
    });
  });
})();
