/* BLE：自定义音频服务，一条 notify 特征发送 Opus 帧。
 * 每个通知包格式: [seq 低字节][seq 高字节][一帧 Opus 数据]
 */
#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <string.h>
#include <zephyr/sys/byteorder.h>
#include "app.h"

/* 服务 UUID: 5a1d0001-7e3c-4b8a-9f00-524947000000 */
#define RIG_UUID(n) BT_UUID_128_ENCODE(0x5a1d0000 | (n), 0x7e3c, 0x4b8a, 0x9f00, 0x524947000000)
static const struct bt_uuid_128 svc_uuid   = BT_UUID_INIT_128(RIG_UUID(1));
static const struct bt_uuid_128 audio_uuid = BT_UUID_INIT_128(RIG_UUID(2));
/* 电池特征 5a1d0003-…：uint16 小端，单位 mV，可读 + 通知 */
static const struct bt_uuid_128 batt_uuid  = BT_UUID_INIT_128(RIG_UUID(3));

static struct bt_conn *cur_conn;
static volatile bool subscribed;

static volatile bool batt_subscribed;

static void batt_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	batt_subscribed = (value == BT_GATT_CCC_NOTIFY);
}

static ssize_t batt_read(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			 void *buf, uint16_t len, uint16_t offset)
{
	uint16_t mv = sys_cpu_to_le16(battery_mv());
	return bt_gatt_attr_read(conn, attr, buf, len, offset, &mv, sizeof(mv));
}

static void ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	subscribed = (value == BT_GATT_CCC_NOTIFY);
	printk("audio notify %s\n", subscribed ? "ON" : "OFF");
}

BT_GATT_SERVICE_DEFINE(rig_svc,
	BT_GATT_PRIMARY_SERVICE(&svc_uuid),
	BT_GATT_CHARACTERISTIC(&audio_uuid.uuid, BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_NONE, NULL, NULL, NULL),
	BT_GATT_CCC(ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	BT_GATT_CHARACTERISTIC(&batt_uuid.uuid, BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ, batt_read, NULL, NULL),
	BT_GATT_CCC(batt_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

static const struct bt_data ad[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
	BT_DATA_BYTES(BT_DATA_UUID128_ALL, RIG_UUID(1)),
};
static const struct bt_data sd[] = {
	BT_DATA(BT_DATA_NAME_COMPLETE, CONFIG_BT_DEVICE_NAME, sizeof(CONFIG_BT_DEVICE_NAME) - 1),
};

static struct k_work adv_work;

static void adv_start(struct k_work *w)
{
	int err = bt_le_adv_start(BT_LE_ADV_CONN_FAST_2, ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
	if (err && err != -EALREADY) {
		printk("adv start failed: %d\n", err);
	} else {
		printk("advertising\n");
	}
}

static void connected(struct bt_conn *conn, uint8_t err)
{
	if (err) {
		printk("connect failed: %u\n", err);
		return;
	}
	cur_conn = bt_conn_ref(conn);
	printk("connected\n");

	/* 请求 2M PHY 和长数据包，省电又提速 */
	bt_conn_le_phy_update(conn, BT_CONN_LE_PHY_PARAM_2M);
	struct bt_conn_le_data_len_param dl = { .tx_max_len = 251, .tx_max_time = 2120 };
	bt_conn_le_data_len_update(conn, &dl);
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
	printk("disconnected (reason %u)\n", reason);
	subscribed = false;
	if (cur_conn) {
		bt_conn_unref(cur_conn);
		cur_conn = NULL;
	}
}

static void recycled(void)
{
	k_work_submit(&adv_work);
}

static void param_updated(struct bt_conn *conn, uint16_t interval, uint16_t latency, uint16_t timeout)
{
	printk("conn params: interval %u.%02u ms, latency %u\n",
	       interval * 125 / 100, (interval * 125) % 100, latency);
}

BT_CONN_CB_DEFINE(conn_cb) = {
	.connected = connected,
	.disconnected = disconnected,
	.recycled = recycled,
	.le_param_updated = param_updated,
};

static void mtu_updated(struct bt_conn *conn, uint16_t tx, uint16_t rx)
{
	printk("MTU: tx %u rx %u\n", tx, rx);
}

static struct bt_gatt_cb gatt_cb = { .att_mtu_updated = mtu_updated };

int ble_init(void)
{
	int err = bt_enable(NULL);
	if (err) {
		return err;
	}
	bt_gatt_cb_register(&gatt_cb);
	k_work_init(&adv_work, adv_start);
	k_work_submit(&adv_work);
	return 0;
}

bool ble_streaming(void)
{
	return cur_conn && subscribed;
}

int ble_send_audio(const uint8_t *data, size_t len)
{
	static uint16_t seq;
	uint8_t pkt[2 + OPUS_MAX_BYTES];

	if (!ble_streaming() || len > OPUS_MAX_BYTES) {
		return -ENOTCONN;
	}
	if (len + 2 > bt_gatt_get_mtu(cur_conn) - 3) {
		return -EMSGSIZE;   /* MTU 还没协商大 */
	}
	pkt[0] = seq & 0xff;
	pkt[1] = seq >> 8;
	memcpy(&pkt[2], data, len);
	seq++;
	return bt_gatt_notify(cur_conn, &rig_svc.attrs[1], pkt, len + 2);
}

int ble_send_battery(uint16_t mv)
{
	if (!cur_conn || !batt_subscribed) {
		return -ENOTCONN;
	}
	uint16_t v = sys_cpu_to_le16(mv);
	return bt_gatt_notify(cur_conn, &rig_svc.attrs[4], &v, sizeof(v));
}
