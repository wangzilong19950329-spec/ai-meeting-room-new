import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import {
  ArrowDown,
  ArrowRight,
  ChatDotRound,
  Check,
  CircleCheck,
  Connection,
  Delete,
  Document,
  Edit,
  Expand,
  Grid,
  Hide,
  Loading,
  Lock,
  Plus,
  QuestionFilled,
  Refresh,
  Search,
  Setting,
  Switch,
  SwitchButton,
  Timer,
  TrophyBase,
  User,
  View,
} from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'

const app = createApp(App)

const icons = {
  ArrowDown,
  ArrowRight,
  ChatDotRound,
  Check,
  CircleCheck,
  Connection,
  Delete,
  Document,
  Edit,
  Expand,
  Grid,
  Hide,
  Loading,
  Lock,
  Plus,
  QuestionFilled,
  Refresh,
  Search,
  Setting,
  Switch,
  SwitchButton,
  Timer,
  TrophyBase,
  User,
  View,
}

for (const [key, component] of Object.entries(icons)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
