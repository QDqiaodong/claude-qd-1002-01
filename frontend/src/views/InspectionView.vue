<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：缺陷点阵 + 返修闭环勾选。
// 每条质检记录把「不良点数」画成一格一格的点阵（一点一格），
// 点阵越铺越满，越过阈值（12 点）整片底色转红并盖上「判废」戳；
// 结论为「返修」的记录右侧是一个闭环勾选框，勾上才算放行。
// 新登记质检走顶部一条常驻录入条。
// ------------------------------------------------------------------

const orders = ref([])
const inspections = ref([])
const error = ref('')
const tip = ref('')
const MAX_POINTS = 12
const form = ref({ orderId: null, stepName: 'AOI', defectType: '偏移', defectPoints: 0, sampleQty: 40, inspector: '孙悦' })
const DEFECTS = ['少件', '偏移', '虚焊', '连锡', '反向']

const openRepairs = computed(() => inspections.value.filter((i) => i.verdict === '返修' && !i.repairDone))
const scrapped = computed(() => inspections.value.filter((i) => i.verdict === '判废').length)

async function load() {
  error.value = ''
  try {
    orders.value = await api.listOrders()
    inspections.value = await api.listInspections()
    if (!form.value.orderId && orders.value.length) form.value.orderId = orders.value[0].id
  } catch (e) { error.value = e.message }
}

async function create() {
  error.value = ''; tip.value = ''
  try {
    const payload = { ...form.value, order: { id: form.value.orderId } }
    const saved = await api.createInspection(payload)
    tip.value = `已登记质检，结论「${saved.verdict}」`
    await load()
  } catch (e) { error.value = e.message }
}

async function repair(id) {
  error.value = ''; tip.value = ''
  try {
    await api.repair(id)
    tip.value = '返修已闭环'
    await load()
  } catch (e) { error.value = e.message }
}

function points(n) {
  const v = Math.min(Number(n) || 0, MAX_POINTS + 3)
  return Array.from({ length: v }, (_, i) => i)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">质检登记</span>
        <span class="panel-desc">不良点数超过 {{ MAX_POINTS }} 点自动判废，有缺陷但未超判返修</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="form-row">
        <select v-model="form.orderId" class="fld">
          <option v-for="o in orders" :key="o.id" :value="o.id">{{ o.orderNo }}</option>
        </select>
        <select v-model="form.stepName" class="fld">
          <option>上料</option><option>贴片</option><option>回流焊</option><option>AOI</option><option>目检</option>
        </select>
        <select v-model="form.defectType" class="fld">
          <option v-for="d in DEFECTS" :key="d" :value="d">{{ d }}</option>
        </select>
        <input type="number" min="0" v-model.number="form.defectPoints" class="fld n" placeholder="不良点数" />
        <input type="number" min="1" v-model.number="form.sampleQty" class="fld n" placeholder="抽检板数" />
        <input v-model="form.inspector" class="fld" style="width:96px" placeholder="检验员" />
        <button class="act primary" @click="create">登记质检</button>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">缺陷点阵</span>
        <span class="panel-desc">一格一点，铺满即判废；返修需勾闭环</span>
      </div>

      <div class="stat-row">
        <div class="stat"><div class="k">质检记录</div><div class="v">{{ inspections.length }}</div></div>
        <div class="stat"><div class="k">判废</div><div class="v">{{ scrapped }}</div></div>
        <div class="stat"><div class="k">未闭环返修</div><div class="v">{{ openRepairs.length }}</div></div>
      </div>

      <div class="insp-list">
        <div
          v-for="i in inspections"
          :key="i.id"
          class="insp-card"
          :class="{ scrap: i.verdict === '判废', repair: i.verdict === '返修' }"
        >
          <div class="insp-top">
            <span class="i-no">{{ i.orderNo }}</span>
            <span class="i-step">{{ i.stepName }}</span>
            <span class="i-type">{{ i.defectType }}</span>
            <span class="i-verdict">{{ i.verdict }}</span>
          </div>

          <div class="dot-matrix">
            <span v-for="p in points(i.defectPoints)" :key="p" class="dot"></span>
            <span v-if="i.defectPoints === 0" class="no-defect">无缺陷</span>
          </div>

          <div class="insp-foot">
            <span>{{ i.defectPoints }} 点 / 抽检 {{ i.sampleQty }} 板 · {{ i.defectRate }}‰ · {{ i.inspector }}</span>
            <label v-if="i.verdict === '返修'" class="close">
              <input type="checkbox" :checked="i.repairDone" :disabled="i.repairDone" @change="repair(i.id)" />
              闭环
            </label>
            <span v-else class="stamp">{{ i.verdict === '判废' ? '判废' : '合格' }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.form-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }

.insp-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 10px; }

.insp-card {
  border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; background: #fff;
  display: flex; flex-direction: column; gap: 8px;
}
.insp-card.scrap { border-color: #ef9a9a; background: #fff6f6; }
.insp-card.repair { border-color: #ffcc80; background: #fffaf0; }

.insp-top { display: flex; align-items: center; gap: 6px; }
.i-no { font-weight: 800; color: var(--primary-deep); font-size: 12px; }
.i-step, .i-type { font-size: 11px; color: var(--muted); }
.i-verdict {
  margin-left: auto; font-size: 10px; font-weight: 800;
  padding: 1px 8px; border-radius: 9px; background: var(--primary-wash); color: var(--primary-deep);
}
.insp-card.scrap .i-verdict { background: #ffcdd2; color: #b71c1c; }
.insp-card.repair .i-verdict { background: #ffe0b2; color: #e65100; }

.dot-matrix {
  display: flex; flex-wrap: wrap; gap: 3px;
  padding: 8px; border-radius: 7px; background: #f5f6fb; min-height: 40px; align-items: center;
}
.insp-card.scrap .dot-matrix { background: #ffebee; }
.dot { width: 11px; height: 11px; border-radius: 2px; background: var(--primary-soft); }
.insp-card.scrap .dot { background: #c62828; }
.insp-card.repair .dot { background: #ef6c00; }
.no-defect { font-size: 12px; color: #2e7d32; font-weight: 700; }

.insp-foot { display: flex; align-items: center; gap: 8px; font-size: 11px; color: var(--muted); }
.insp-foot .stamp { margin-left: auto; font-weight: 800; color: var(--primary-deep); }
.insp-foot .close { margin-left: auto; display: flex; align-items: center; gap: 4px; cursor: pointer; }
</style>
