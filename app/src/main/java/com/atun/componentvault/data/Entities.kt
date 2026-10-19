package com.atun.componentvault.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 库存器件。型号允许为空 —— 老库存/散料常常只有内部编号或丝印，
 * 强制型号必填会让用户放弃录入，这是库存类 App 最常见的失败点。
 */
@Entity(
    tableName = "part",
    indices = [
        Index(value = ["internalCode"], unique = true),
        Index(value = ["mpn"]),
        Index(value = ["category"]),
        Index(value = ["packageName"])
    ]
)
data class Part(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 内部编号，形如 CV-000123，永远不为空 */
    val internalCode: String,
    /** 型号/丝印，可空 */
    val mpn: String = "",
    /** 名称，如「贴片电阻」 */
    val name: String = "",
    /** 分类，如「电阻」 */
    val category: String = "",
    /** 封装，如「0603 (1608)」 */
    val packageName: String = "",
    /** 品牌 */
    val brand: String = "",
    /** 立创商城元件编号，如 C8734。业务标识，人读用 */
    val lcscCode: String = "",
    /**
     * 立创商城商品页数字 ID（搜索接口的 id 字段）。
     * 商品页链接唯一正确格式是 https://item.szlcsc.com/{id}.html ，
     * 用立创号拼会落到软 404（HTTP 200 但页面是"没有找到"），所以必须单独存。
     */
    val lcscProductId: Long = 0,
    /** 数据手册地址 */
    val datasheetUrl: String = "",
    /** 动态参数，JSON 对象字符串，key 由分类的 ParamDef 决定 */
    val paramsJson: String = "{}",
    /** 功能标签，逗号分隔，如「上拉,限流」 */
    val tags: String = "",
    /** 自动生成的一句话简介 */
    val summary: String = "",
    /** 当前库存数量 */
    val qty: Int = 0,
    /** 低库存阈值 */
    val lowThreshold: Int = 10,
    /** 仓位，如「A-03-2」 */
    val location: String = "",
    /** 参考单价（元） */
    val price: Double = 0.0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isOut: Boolean get() = qty <= 0
    val isLow: Boolean get() = qty in 1..lowThreshold
    val tagList: List<String> get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * 出入库流水。before / after / delta 三个值同时记录，
 * 是为了在局域网多端并发写入时能审计出「谁把数量改错了」，也能做幂等重放。
 */
@Entity(
    tableName = "txn",
    indices = [Index(value = ["partId"]), Index(value = ["idempotencyKey"], unique = false)]
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val partId: Long,
    /** IN 入库 / OUT 出库 / ADJ 盘点调整 / CREATE 新建 */
    val type: String,
    val delta: Int,
    val before: Int,
    val after: Int,
    /** 关联单据，如 BOM 名称、工单号 */
    val ref: String = "",
    val note: String = "",
    /** 幂等键：局域网重试时避免重复扣减 */
    val idempotencyKey: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 分类的动态参数定义。新增一个分类只需要插数据，不需要改代码、不用发版。
 * 这是整套方案能覆盖「电阻 / 电容 / MCU / 连接器」等差异极大的品类的前提。
 */
@Entity(tableName = "param_def", primaryKeys = ["category", "key"])
data class ParamDef(
    val category: String,
    val key: String,
    val label: String,
    val unit: String = "",
    /** text / number / enum */
    val type: String = "text",
    val order: Int = 0
)

/** 界面用的库存概览统计 */
data class StockStats(
    val totalKinds: Int = 0,
    val totalQty: Long = 0,
    val lowKinds: Int = 0,
    val outKinds: Int = 0
)
