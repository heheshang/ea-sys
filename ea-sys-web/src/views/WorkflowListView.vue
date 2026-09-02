<template>
  <el-empty description="暂无工作流" :image-size="120">
    <el-button type="primary" @click="$router.push('/canvas')">新建画布</el-button>
  </el-empty>
</template>

<script setup lang="ts">
// M0：工作流列表空态。M2 画布/服务就绪后接 GET /api/workflows 列表。
</script>