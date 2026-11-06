package com.goldentime.api.commerce.voucher;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class VoucherRecords {
    private VoucherRecords() {}

    public record CreateVoucherRequest(
            @NotBlank(message = "Mã voucher không được để trống.")
            @Size(max = 50, message = "Mã voucher tối đa 50 ký tự.")
            String code,

            @NotBlank(message = "Tên voucher không được để trống.")
            @Size(max = 255, message = "Tên voucher tối đa 255 ký tự.")
            String name,

            @Size(max = 2000, message = "Mô tả tối đa 2000 ký tự.")
            String description,

            @NotBlank(message = "Loại giảm giá không được để trống.")
            String discountType,

            @NotNull(message = "Giá trị giảm giá không được để trống.")
            @Positive(message = "Giá trị giảm giá phải lớn hơn 0.")
            BigDecimal discountValue,

            @Positive(message = "Mức giảm tối đa phải lớn hơn 0.")
            Long maxDiscountAmount,

            @Min(value = 0, message = "Giá trị đơn tối thiểu không được âm.")
            Long minOrderValue,

            @NotNull(message = "Tổng số lượng phát hành không được để trống.")
            @Min(value = 0, message = "Tổng số lượng phát hành không được âm.")
            Integer totalQuantity,

            @NotNull(message = "Giới hạn nhận mỗi người không được để trống.")
            @Min(value = 1, message = "Giới hạn nhận mỗi người tối thiểu là 1.")
            Integer perUserClaimLimit,

            @NotNull(message = "Giới hạn sử dụng mỗi người không được để trống.")
            @Min(value = 1, message = "Giới hạn sử dụng mỗi người tối thiểu là 1.")
            Integer perUserUseLimit,

            Instant claimStartAt,
            Instant claimEndAt,
            Instant validFrom,

            @NotNull(message = "Thời điểm hết hạn không được để trống.")
            Instant validUntil
    ) {}

    public record UpdateVoucherRequest(
            @NotBlank(message = "Tên voucher không được để trống.")
            @Size(max = 255, message = "Tên voucher tối đa 255 ký tự.")
            String name,

            @Size(max = 2000, message = "Mô tả tối đa 2000 ký tự.")
            String description,

            @Min(value = 0, message = "Tổng số lượng phát hành không được âm.")
            Integer totalQuantity,

            @Min(value = 1, message = "Giới hạn nhận mỗi người tối thiểu là 1.")
            Integer perUserClaimLimit,

            @Min(value = 1, message = "Giới hạn sử dụng mỗi người tối thiểu là 1.")
            Integer perUserUseLimit,

            Instant claimStartAt,
            Instant claimEndAt,
            Instant validFrom,

            @NotNull(message = "Thời điểm hết hạn không được để trống.")
            Instant validUntil,

            Boolean enabled
    ) {}

    public record VoucherResponse(
            long id,
            String code,
            String name,
            String description,
            String discountType,
            BigDecimal discountValue,
            Long maxDiscountAmount,
            long minOrderValue,
            long shopId,
            String shopName,
            int totalQuantity,
            int claimedCount,
            int usedCount,
            int perUserClaimLimit,
            int perUserUseLimit,
            Instant claimStartAt,
            Instant claimEndAt,
            Instant validFrom,
            Instant validUntil,
            boolean enabled,
            Instant createdAt
    ) {}

    public record BuyerVoucherView(
            long buyerVoucherId,
            long voucherId,
            String code,
            String name,
            String description,
            String discountType,
            BigDecimal discountValue,
            Long maxDiscountAmount,
            long minOrderValue,
            long shopId,
            String shopName,
            String grantType,
            String status,
            Instant validFrom,
            Instant validUntil,
            Instant claimedAt,
            Instant usedAt
    ) {}

    public record ApplicableVoucherItem(
            long buyerVoucherId,
            long voucherId,
            String code,
            String name,
            String discountType,
            BigDecimal discountValue,
            Long maxDiscountAmount,
            long minOrderValue,
            long shopId,
            long estimatedDiscount,
            boolean applicable,
            String reasonNotApplicable
    ) {}

    public record ValidatedVoucher(
            long buyerVoucherId,
            long voucherId,
            long shopId,
            long discountAmount
    ) {}
}
