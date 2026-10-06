let currentPage = 1;
let totalPages = 0;
let searchKeyword = '';
let selectedCategory = '';
let selectedStatus = '';
let sortBy = 'default';

async function init() {
    try {
        const r = await fetch('/api/auth/me');
        const x = await r.json();
        if (x.code !== 200) {
            location.href = '/login';
            return;
        }
        document.getElementById('userName').textContent = x.data.userName;
    } catch (e) {
        location.href = '/login';
    }
    await loadStats();
    await loadCategories();
    await loadProducts();
}

async function loadStats() {
    try {
        const r = await fetch('/api/shop/stats');
        const x = await r.json();
        if (x.code === 200) {
            document.getElementById('statTotal').textContent = x.data.total;
            document.getElementById('statOn').textContent = x.data.onSale;
            document.getElementById('statOff').textContent = x.data.offSale;
        }
    } catch (e) {
        console.error(e);
    }
}

async function loadCategories() {
    try {
        const r = await fetch('/api/shop/category/list');
        const x = await r.json();
        if (x.code === 200) {
            const cats = x.data;
            const select = document.getElementById('categoryFilter');
            const parents = cats.filter(c => !c.parentCategoryId || c.parentCategoryId === 0);
            const children = cats.filter(c => c.parentCategoryId && c.parentCategoryId > 0);
            let html = '<option value="">全部分类</option>';
            parents.forEach(p => {
                html += `<option value="${p.id}">${p.name}</option>`;
                children.filter(c => c.parentCategoryId === p.id).forEach(ch => {
                    html += `<option value="${ch.id}">  └ ${ch.name}</option>`;
                });
            });
            select.innerHTML = html;
        }
    } catch (e) {
        console.error(e);
    }
}

async function loadProducts() {
    try {
        const url = `/api/shop/list?pageNum=${currentPage}&pageSize=12`;
        const r = await fetch(url);
        const x = await r.json();
        if (x.code === 200) {
            const data = x.data;
            renderProducts(data.data);
            renderPagination(data.totalPage, data.totalCount, data.pageSize);
        }
    } catch (e) {
        console.error(e);
    }
}

function renderProducts(products) {
    const grid = document.getElementById('productGrid');
    if (!products || products.length === 0) {
        grid.innerHTML = '<div style="text-align:center;padding:60px;color:#718078">暂无商品，点击上方按钮发布商品</div>';
        return;
    }
    grid.innerHTML = products.map(p => {
        const images = p.images ? JSON.parse(p.images) : [];
        const firstImage = images.length > 0 ? images[0] : null;
        const categories = p.categoryIds ? JSON.parse(p.categoryIds) : [];
        const catHtml = categories.length > 0 ? 
            `<div class="category-tags">${categories.slice(0, 3).map(c => `<span class="category-tag">${c}</span>`).join('')}</div>` : '';
        return `
            <div class="card" onclick="showDetail(${p.id})">
                <div class="status ${p.status.toLowerCase()}">${p.status === 'ON' ? '在售' : '已下架'}</div>
                <div class="card-img">${firstImage ? `<img src="${firstImage}" alt="${p.name}">` : '<span class="no-img">📦</span>'}</div>
                <h4>${p.name}</h4>
                <div class="desc">${p.description || '暂无描述'}</div>
                ${catHtml}
                <div class="meta">
                    <div class="price">¥${p.price.toFixed(2)}</div>
                    <div class="stock">库存: ${p.stock}</div>
                </div>
            </div>
        `;
    }).join('');
}

function renderPagination(totalPage, totalCount, pageSize) {
    totalPages = totalPage;
    const pagination = document.getElementById('pagination');
    if (totalPage <= 1) {
        pagination.innerHTML = '';
        return;
    }
    let html = `<button onclick="goPage(${currentPage - 1})" ${currentPage <= 1 ? 'disabled' : ''}>上一页</button>`;
    html += `<span class="page-info">${currentPage}/${totalPage} 共${totalCount}条</span>`;
    html += `<button onclick="goPage(${currentPage + 1})" ${currentPage >= totalPage ? 'disabled' : ''}>下一页</button>`;
    pagination.innerHTML = html;
}

function goPage(page) {
    if (page < 1 || page > totalPages) return;
    currentPage = page;
    loadProducts();
}

function handleSearch() {
    searchKeyword = document.getElementById('searchInput').value.trim();
    currentPage = 1;
    loadProducts();
}

function handleFilter() {
    selectedCategory = document.getElementById('categoryFilter').value;
    selectedStatus = document.getElementById('statusFilter').value;
    currentPage = 1;
    loadProducts();
}

function handleSort() {
    sortBy = document.getElementById('sortFilter').value;
    currentPage = 1;
    loadProducts();
}

function resetFilters() {
    document.getElementById('searchInput').value = '';
    document.getElementById('categoryFilter').value = '';
    document.getElementById('statusFilter').value = '';
    document.getElementById('sortFilter').value = 'default';
    searchKeyword = '';
    selectedCategory = '';
    selectedStatus = '';
    sortBy = 'default';
    currentPage = 1;
    loadProducts();
}

async function showDetail(id) {
    try {
        const r = await fetch(`/api/shop/detail/${id}`);
        const x = await r.json();
        if (x.code === 200) {
            const p = x.data;
            const images = p.images ? JSON.parse(p.images) : [];
            const detailImages = p.detail ? JSON.parse(p.detail) : [];
            const categories = p.categoryIds ? JSON.parse(p.categoryIds) : [];
            let html = `
                <div class="modal-img">${images.length > 0 ? `<img src="${images[0]}" alt="${p.name}">` : '<span class="no-img" style="font-size:40px">📦</span>'}</div>
                <h3 style="margin:0 0 12px;color:#24342b">${p.name}</h3>
                <div class="modal-desc">${p.description || '暂无描述'}</div>
                <div class="modal-details">
                    <div class="detail-item"><div class="label">价格</div><div class="value price">¥${p.price.toFixed(2)}</div></div>
                    <div class="detail-item"><div class="label">库存</div><div class="value">${p.stock}</div></div>
                    <div class="detail-item"><div class="label">状态</div><div class="value">${p.status === 'ON' ? '<span style="color:#2e7d32">在售</span>' : '<span style="color:#e65100">已下架</span>'}</div></div>
                    <div class="detail-item"><div class="label">分类</div><div class="value">${categories.length > 0 ? categories.join(', ') : '未分类'}</div></div>
                </div>
            `;
            if (detailImages.length > 0) {
                html += `<div style="margin-top:20px"><div style="font-size:14px;font-weight:600;color:#496256;margin-bottom:10px">商品详情</div>`;
                detailImages.forEach(img => {
                    html += `<img src="${img}" style="width:100%;border-radius:10px;margin-bottom:10px" alt="详情图">`;
                });
                html += '</div>';
            }
            document.getElementById('modalBody').innerHTML = html;
            document.getElementById('modal').classList.add('active');
        } else {
            toast('商品不存在');
        }
    } catch (e) {
        toast('获取详情失败');
    }
}

function closeModal() {
    document.getElementById('modal').classList.remove('active');
}

function toast(s) {
    const e = document.getElementById('toast');
    e.textContent = s;
    e.style.display = 'block';
    setTimeout(() => e.style.display = 'none', 2600);
}

async function logout() {
    await fetch('/api/auth/logout', { method: 'POST' });
    location.href = '/login';
}

init();