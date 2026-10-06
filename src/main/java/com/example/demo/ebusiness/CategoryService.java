package com.example.demo.ebusiness;

import com.example.demo.chat.entity.Category;
import com.example.demo.chat.repository.mysql.CategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryService {

    @Autowired
    private CategoryRepository categoryRepository;

    public Category save(Category category) {
        if (category.getParentCategoryId() == null) {
            category.setParentCategoryId(0L);
        }
        return categoryRepository.save(category);
    }

    public List<Category> findAll() {
        if (categoryRepository.count() == 0) {
            initDefaultCategories();
        }
        return categoryRepository.findAllByOrderByParentCategoryIdAscIdAsc();
    }

    public Category findById(Long id) {
        return categoryRepository.findById(id).orElse(null);
    }

    public List<Category> findByParentId(Long parentId) {
        return categoryRepository.findByParentCategoryId(parentId);
    }

    public void initDefaultCategories() {
        if (categoryRepository.count() == 0) {
            Category cat1 = new Category();
            cat1.setName("植物用品");
            cat1.setDescription("绿植养护相关用品");
            cat1.setParentCategoryId(0L);
            Category savedCat1 = categoryRepository.save(cat1);

            Category cat2 = new Category();
            cat2.setName("宠物用品");
            cat2.setDescription("宠物护理相关用品");
            cat2.setParentCategoryId(0L);
            Category savedCat2 = categoryRepository.save(cat2);

            Category cat3 = new Category();
            cat3.setName("肥料营养");
            cat3.setDescription("植物所需的各类肥料和营养液");
            cat3.setParentCategoryId(0L);
            Category savedCat3 = categoryRepository.save(cat3);

            Category cat4 = new Category();
            cat4.setName("花盆容器");
            cat4.setDescription("各类花盆和种植容器");
            cat4.setParentCategoryId(0L);
            Category savedCat4 = categoryRepository.save(cat4);

            Category cat5 = new Category();
            cat5.setName("园艺工具");
            cat5.setDescription("园艺种植工具");
            cat5.setParentCategoryId(0L);
            Category savedCat5 = categoryRepository.save(cat5);

            Category cat6 = new Category();
            cat6.setName("宠物食品");
            cat6.setDescription("宠物粮食和零食");
            cat6.setParentCategoryId(0L);
            Category savedCat6 = categoryRepository.save(cat6);

            Category sub1 = new Category();
            sub1.setName("营养液");
            sub1.setDescription("植物生长营养液");
            sub1.setParentCategoryId(savedCat1.getId());
            categoryRepository.save(sub1);

            Category sub2 = new Category();
            sub2.setName("植物药剂");
            sub2.setDescription("杀虫、杀菌药剂");
            sub2.setParentCategoryId(savedCat1.getId());
            categoryRepository.save(sub2);

            Category sub3 = new Category();
            sub3.setName("盆栽植物");
            sub3.setDescription("各类盆栽绿植");
            sub3.setParentCategoryId(savedCat1.getId());
            categoryRepository.save(sub3);

            Category sub4 = new Category();
            sub4.setName("种子种苗");
            sub4.setDescription("植物种子和幼苗");
            sub4.setParentCategoryId(savedCat1.getId());
            categoryRepository.save(sub4);

            Category sub5 = new Category();
            sub5.setName("宠物玩具");
            sub5.setDescription("宠物娱乐玩具");
            sub5.setParentCategoryId(savedCat2.getId());
            categoryRepository.save(sub5);

            Category sub6 = new Category();
            sub6.setName("宠物洗护");
            sub6.setDescription("宠物洗浴用品");
            sub6.setParentCategoryId(savedCat2.getId());
            categoryRepository.save(sub6);

            Category sub7 = new Category();
            sub7.setName("宠物窝垫");
            sub7.setDescription("宠物床和垫子");
            sub7.setParentCategoryId(savedCat2.getId());
            categoryRepository.save(sub7);

            Category sub8 = new Category();
            sub8.setName("宠物服饰");
            sub8.setDescription("宠物衣服和配饰");
            sub8.setParentCategoryId(savedCat2.getId());
            categoryRepository.save(sub8);

            Category sub9 = new Category();
            sub9.setName("有机肥料");
            sub9.setDescription("天然有机肥料");
            sub9.setParentCategoryId(savedCat3.getId());
            categoryRepository.save(sub9);

            Category sub10 = new Category();
            sub10.setName("复合肥");
            sub10.setDescription("复合化学肥料");
            sub10.setParentCategoryId(savedCat3.getId());
            categoryRepository.save(sub10);

            Category sub11 = new Category();
            sub11.setName("缓释肥");
            sub11.setDescription("长效缓释肥料");
            sub11.setParentCategoryId(savedCat3.getId());
            categoryRepository.save(sub11);

            Category sub12 = new Category();
            sub12.setName("微量元素");
            sub12.setDescription("植物微量元素补充剂");
            sub12.setParentCategoryId(savedCat3.getId());
            categoryRepository.save(sub12);

            Category sub13 = new Category();
            sub13.setName("陶瓷花盆");
            sub13.setDescription("精美陶瓷花盆");
            sub13.setParentCategoryId(savedCat4.getId());
            categoryRepository.save(sub13);

            Category sub14 = new Category();
            sub14.setName("塑料花盆");
            sub14.setDescription("轻便塑料花盆");
            sub14.setParentCategoryId(savedCat4.getId());
            categoryRepository.save(sub14);

            Category sub15 = new Category();
            sub15.setName("多肉花盆");
            sub15.setDescription("多肉专用花盆");
            sub15.setParentCategoryId(savedCat4.getId());
            categoryRepository.save(sub15);

            Category sub16 = new Category();
            sub16.setName("吊盆");
            sub16.setDescription("悬挂式花盆");
            sub16.setParentCategoryId(savedCat4.getId());
            categoryRepository.save(sub16);

            Category sub17 = new Category();
            sub17.setName("园艺剪刀");
            sub17.setDescription("修剪工具");
            sub17.setParentCategoryId(savedCat5.getId());
            categoryRepository.save(sub17);

            Category sub18 = new Category();
            sub18.setName("浇水壶");
            sub18.setDescription("园艺浇水工具");
            sub18.setParentCategoryId(savedCat5.getId());
            categoryRepository.save(sub18);

            Category sub19 = new Category();
            sub19.setName("土壤检测");
            sub19.setDescription("土壤湿度、肥力检测工具");
            sub19.setParentCategoryId(savedCat5.getId());
            categoryRepository.save(sub19);

            Category sub20 = new Category();
            sub20.setName("园艺手套");
            sub20.setDescription("防护手套");
            sub20.setParentCategoryId(savedCat5.getId());
            categoryRepository.save(sub20);

            Category sub21 = new Category();
            sub21.setName("猫粮");
            sub21.setDescription("各类猫咪粮食");
            sub21.setParentCategoryId(savedCat6.getId());
            categoryRepository.save(sub21);

            Category sub22 = new Category();
            sub22.setName("狗粮");
            sub22.setDescription("各类狗狗粮食");
            sub22.setParentCategoryId(savedCat6.getId());
            categoryRepository.save(sub22);

            Category sub23 = new Category();
            sub23.setName("宠物零食");
            sub23.setDescription("宠物训练零食");
            sub23.setParentCategoryId(savedCat6.getId());
            categoryRepository.save(sub23);

            Category sub24 = new Category();
            sub24.setName("宠物保健品");
            sub24.setDescription("宠物营养补充剂");
            sub24.setParentCategoryId(savedCat6.getId());
            categoryRepository.save(sub24);
        }
    }
}