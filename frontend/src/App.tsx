import { BookOutlined, FileAddOutlined, LinkOutlined, SearchOutlined } from '@ant-design/icons'
import { NavLink, Route, Routes } from 'react-router'
import HomePage from './pages/HomePage'
import PackageCreatePage from './pages/PackageCreatePage'
import PackageDetailPage from './pages/PackageDetailPage'
import VideoCreatePage from './pages/VideoCreatePage'
import AskPage from './pages/AskPage'

export default function App() {
  return (
    <div className="app-shell">
      <header className="site-header">
        <NavLink to="/" className="brand">
          <span className="brand-mark">ASH</span>
          <span>
            <strong>AI Sources Handler</strong>
            <small>学习资料整理调度站</small>
          </span>
        </NavLink>
        <nav>
          <NavLink to="/" end>
            <BookOutlined /> 资料库
          </NavLink>
          <NavLink to="/packages/new">
            <FileAddOutlined /> 新建资料包
          </NavLink>
          <NavLink to="/videos/new">
            <LinkOutlined /> B 站视频
          </NavLink>
          <NavLink to="/ask">
            <SearchOutlined /> 全库问答
          </NavLink>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/packages/new" element={<PackageCreatePage />} />
          <Route path="/videos/new" element={<VideoCreatePage />} />
          <Route path="/packages/:packageId" element={<PackageDetailPage />} />
          <Route path="/ask" element={<AskPage />} />
        </Routes>
      </main>
      <footer>
        <span>LOCAL / SINGLE USER</span>
        <span>来源优先，结论可追溯</span>
      </footer>
    </div>
  )
}

