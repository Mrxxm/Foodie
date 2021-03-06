package com.kenrou.service.impl;

import com.kenrou.enums.OrderStatusEnum;
import com.kenrou.enums.PayMethod;
import com.kenrou.enums.YesOrNo;
import com.kenrou.mapper.OrderItemsMapper;
import com.kenrou.mapper.OrderStatusMapper;
import com.kenrou.mapper.OrdersMapper;
import com.kenrou.pojo.*;
import com.kenrou.pojo.bo.ShopcartBO;
import com.kenrou.pojo.bo.SubmitOrderBO;
import com.kenrou.pojo.vo.MerchantOrderVO;
import com.kenrou.pojo.vo.OrderVo;
import com.kenrou.service.AddressService;
import com.kenrou.service.ItemService;
import com.kenrou.service.OrderService;
import com.kenrou.utils.DateUtil;
import com.kenrou.utils.MD5Utils;
import org.n3r.idworker.Sid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.awt.*;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrdersMapper ordersMapper;
    @Autowired
    private Sid sid;
    @Autowired
    private AddressService addressService;
    @Autowired
    private ItemService itemService;
    @Autowired
    private OrderItemsMapper orderItemsMapper;
    @Autowired
    private OrderStatusMapper orderStatusMapper;

    @Transactional(propagation = Propagation.REQUIRED)
    @Override
    public OrderVo createOrder(List<ShopcartBO> shopCartList, SubmitOrderBO submitOrderBO) {

        String userId = submitOrderBO.getUserId();
        String addressId = submitOrderBO.getAddressId();
        String itemSpecIds = submitOrderBO.getItemSpecIds();
        Integer payMethod = submitOrderBO.getPayMethod();
        String leftMsg = submitOrderBO.getLeftMsg();
        Integer postAmount = 0; // 包邮费用设置为零
        String orderId = sid.nextShort();
        UserAddress userAddress = addressService.queryUserAddress(userId, addressId);

        // 1.新订单数据保存
        Orders newOrder = new Orders();
        newOrder.setId(orderId);
        newOrder.setUserId(userId);
        newOrder.setReceiverName(userAddress.getReceiver());
        newOrder.setReceiverMobile(userAddress.getMobile());
        newOrder.setReceiverAddress(userAddress.getProvince() + " " + userAddress.getCity() + " " + userAddress.getDistrict() + " " + userAddress.getDetail());
//        newOrder.setTotalAmount();
//        newOrder.setRealPayAmount();
        newOrder.setPostAmount(postAmount);
        newOrder.setPayMethod(payMethod);
        newOrder.setLeftMsg(leftMsg);
        newOrder.setIsComment(YesOrNo.NO.type);
        newOrder.setIsDelete(YesOrNo.NO.type);
        newOrder.setCreatedTime(new Date());
        newOrder.setUpdatedTime(new Date());

        // 2.循环根据itemSpecIds保存订单商品信息表
        String itemSpecIdArr[] = itemSpecIds.split(",");
        Integer totalAmount = 0; // 商品原价累计
        Integer realPayAmount = 0; // 优惠后实际支付价格累计

        // TODO 整合redis，用作删除购物车中已下单数据 - 已完成
        List<ShopcartBO> toBeRemovedShopCartList = new ArrayList<>();
        for (String itemSpecId : itemSpecIdArr) {

            // TODO 整合redis后，商品购买的数量从redis的购物车中获取 - 已完成
            ShopcartBO cart = getBuyCountsFromShopcart(shopCartList, itemSpecId);
            int buyCounts = cart.getBuyCounts();
            toBeRemovedShopCartList.add(cart);

            // 2.1 根据规格id，查询规格的具体信息，主要查询价格
            ItemsSpec itemsSpec = itemService.queryItemsSpecById(itemSpecId);
            totalAmount += itemsSpec.getPriceNormal() * buyCounts;
            realPayAmount += itemsSpec.getPriceDiscount() * buyCounts;

            // 2.2 根据规格id，查询商品信息以及商品图片
            String itemId = itemsSpec.getItemId();
            Items item = itemService.queryItemById(itemId); // 商品信息
            String itemMainImg = itemService.queryItemMainImgByItemId(itemId); // 商品图片

            // 2.3 循环保存子订单到数据库
            OrderItems subOrderItems = new OrderItems();
            String subOrderId = sid.nextShort();
            subOrderItems.setId(subOrderId);
            subOrderItems.setOrderId(orderId);
            subOrderItems.setItemId(itemId);
            subOrderItems.setItemName(item.getItemName());
            subOrderItems.setItemImg(itemMainImg);
            subOrderItems.setBuyCounts(buyCounts);
            subOrderItems.setItemSpecId(itemSpecId);
            subOrderItems.setItemSpecName(itemsSpec.getName());
            subOrderItems.setPrice(itemsSpec.getPriceDiscount());

            orderItemsMapper.insert(subOrderItems);

            // 2.4 扣除库存规格表中
            itemService.decreaseItemSpecStock(itemSpecId, buyCounts);
        }

        newOrder.setTotalAmount(totalAmount);
        newOrder.setRealPayAmount(realPayAmount);

        ordersMapper.insert(newOrder);

        // 3.保存订单状态表
        OrderStatus waitPayOrderStatus = new OrderStatus();
        waitPayOrderStatus.setOrderId(orderId);
        waitPayOrderStatus.setOrderStatus(OrderStatusEnum.WAIT_PAY.type);
        waitPayOrderStatus.setCreatedTime(new Date());

        orderStatusMapper.insert(waitPayOrderStatus);

        // 4.构建商户订单，用于传给支付中心
        MerchantOrderVO merchantOrderVO = new MerchantOrderVO();
        Integer totalPrice = (realPayAmount) + postAmount; // 支付价格
        Long time = System.currentTimeMillis() / 1000; // 时间戳
        try {
            String tokenStr = time + "&mp" + "1" + "&mp" + "m*68+098_q1";
            String token = DigestUtils.md5DigestAsHex(tokenStr.getBytes());

            merchantOrderVO.setToken(token);
        } catch (Exception e) {
            e.printStackTrace();
        }
        merchantOrderVO.setApp_id(1);
        merchantOrderVO.setTime(time);
        merchantOrderVO.setUser_id(submitOrderBO.getUserId());
        merchantOrderVO.setBody("测试");
        merchantOrderVO.setOrder_no(orderId);
        merchantOrderVO.setTotal_price(totalPrice.toString());
        if (submitOrderBO.getPayMethod() == PayMethod.WEIXIN.type) {
            merchantOrderVO.setServe_type("wechat");
            merchantOrderVO.setPay_type("scan");
        }

        // 5.构建自定义订单VO
        OrderVo orderVo = new OrderVo();
        orderVo.setOrderId(orderId);
        orderVo.setMerchantOrderVO(merchantOrderVO);
        orderVo.setToBeRemovedShopCartList(toBeRemovedShopCartList);

        return orderVo;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    @Override
    public void updateOrderStatus(String orderId, Integer orderStatus) {
        OrderStatus order = new OrderStatus();
        order.setOrderId(orderId);
        order.setOrderStatus(orderStatus);
        order.setPayTime(new Date());

        orderStatusMapper.updateByPrimaryKeySelective(order);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    @Override
    public Orders queryOrderById(String orderId) {

        return ordersMapper.selectByPrimaryKey(orderId);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    @Override
    public OrderStatus queryOrderStatusByOrderId(String orderId) {
        OrderStatus orderStatus = new OrderStatus();
        orderStatus.setOrderId(orderId);

        return orderStatusMapper.selectOne(orderStatus);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    @Override
    public void closeOrder() {

        // 查询所有未付款订单，判断时间是否超时(2hours)
        OrderStatus queryOrder = new OrderStatus();
        queryOrder.setOrderStatus(OrderStatusEnum.WAIT_PAY.type);

        List<OrderStatus> list = orderStatusMapper.select(queryOrder);
        for (OrderStatus os: list) {
            // 获得订单创建时间
            Date createdTime = os.getCreatedTime();
            Long time = System.currentTimeMillis() / 1000; // 时间戳
            if (createdTime != null) {
                Long createdTimeStamp = (new SimpleDateFormat(
                        DateUtil.DATETIME_PATTERN)).parse(DateUtil.dateToString(createdTime, DateUtil.DATETIME_PATTERN), new ParsePosition(0)).getTime() / 1000;
                Long hours = (time - createdTimeStamp) / 60;
                System.out.println(hours);
                System.out.println(os.getOrderId());
                if (hours > 3) {
                    // 3分钟超时
                    doCloseOrder(os.getOrderId());
                }
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    void doCloseOrder(String orderId) {
        OrderStatus close = new OrderStatus();
        close.setOrderId(orderId);
        close.setOrderStatus(OrderStatusEnum.CLOSE.type);
        close.setCloseTime(new Date());

        orderStatusMapper.updateByPrimaryKeySelective(close);
    }

    // 从redis中的购物车获取商品，目的：获取counts
    private ShopcartBO getBuyCountsFromShopcart(List<ShopcartBO> shopcartList, String specId) {
        for (ShopcartBO cart: shopcartList) {
            if (cart.getSpecId().equals(specId)) {
                return cart;
            }
        }
        return null;
    }
}
