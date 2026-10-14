# Nanodot

<p align="center"><img src="../../assets/brand/nanodot-mark.svg" alt="Nanodot · Dot" width="112" height="112"></p>

[English](https://github.com/Mola-maker/nanodot/blob/main/README.md) | [简体中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md) | [繁體中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh-TW.md) | [Español](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_es.md) | [Français](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_fr.md) | [Bahasa Indonesia](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_id.md) | [日本語](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ja.md) | [한국어](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ko.md) | [Русский](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ru.md) | [Tiếng Việt](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_vi.md)

Nanodot là tác tử cá nhân mã nguồn mở dựa trên [nanoMuse](https://github.com/nano-muse/nanoMuse), với ứng dụng điện thoại dựa trên [OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis). Trợ lý mặc định là **Dot**. Kho mã chứa Android, iOS, ứng dụng máy tính, bảng điều khiển Web và môi trường Python.

## Bản phát hành thử Android · 1.1.0 Crystal

**[Tải APK Android](https://github.com/Mola-maker/nanodot/releases/download/v1.1.0/Nanodot-1.1.0-arm64.apk)** · [Bản phát hành và mã kiểm tra](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0) · [Ghi chú phát hành](../releases/v1.1.0.md)

Bản phát hành thử Android đầu tiên của Nanodot dành cho **Android 8.0+ / ARM64**: phiên bản **1.1.0**, versionCode **45**. `Nanodot-1.1.0-arm64.apk` dùng khóa ký phát hành riêng của Nanodot. Ghi chú phát hành hướng dẫn cài đặt và kiểm tra tệp.

Bản xem trước này có quả cầu pha lê Dot, trò chuyện nổi trong bảng kính xanh, câu hỏi về vùng màn hình đã chọn và hướng dẫn Care. Mô hình được cấu hình rõ ràng là hỗ trợ hình ảnh nhận ảnh đã che phần ngoài vùng chọn; mô hình khác dùng văn bản có thể lấy được trong vùng chọn. Care giữ hướng dẫn trong cuộc trò chuyện và cung cấp xác nhận bước cùng Stop.

Giao diện theo hệ thống; tên, SOUL.md, ảnh đại diện, hoạt ảnh, mô hình và tùy chọn riêng được giữ lại. Giọng nói liên tục và cập nhật tự động mặc định tắt.

**Giới hạn của bản thử:** các kiểm chứng trước đây dùng trình giả lập API 35 và tình huống có kiểm soát, không thay thế kiểm chứng APK phát hành này trên thiết bị thật. Tác vụ Care hoàn chỉnh với nhà cung cấp mô hình thật, ứng dụng bất kỳ, thiết bị ARM64, giọng nói tiếng Trung và kiểm chứng hiện tại của nền tảng khác vẫn chưa hoàn tất. Xem [hồ sơ kiểm chứng](../android-verification.md).

## Dựng và cài đặt

Dựng Android cục bộ từ checkout này. Gói debug và release riêng biệt; release cần khóa ký riêng và không thay bằng khóa debug. Script không xuất bản, tải lên hay tạo tag.

```sh
bash scripts/build-nanodot-apk.sh debug
# Dedicated local signing configuration is required for release.
bash scripts/build-nanodot-apk.sh release
```

Đọc [hướng dẫn dựng](../build.md), [hợp đồng thương hiệu](../brand.md) và [tài liệu](../index.md). Bản xem trước đã ký có trong [bản phát hành Nanodot 1.1.0](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0). Các bản tải và TestFlight nanoMuse thuộc sản phẩm khác.

Android dùng `io.github.nanodot.app` và `nanodot://`. Giữ các gói nội bộ `com.openminis.app`, `io.github.nanomuse.*`, đường dẫn `minis://` và đường dẫn sandbox. ID mới có vùng lưu trữ riêng; dữ liệu nanoMuse cần xuất/nhập rõ ràng.

## Mô hình và dịch vụ

Dùng nhà cung cấp mô hình của bạn hoặc cấu hình tích hợp relay hiện có. **nanoMuse Cloud** giữ tên và endpoint của dịch vụ gốc. Nanodot không tuyên bố vận hành dịch vụ hay hứa hạn mức miễn phí; tài khoản và chính sách dữ liệu do nhà cung cấp quyết định.

## Đóng góp và giấy phép

Báo lỗi của fork này qua [Nanodot issues](https://github.com/Mola-maker/nanodot/issues). Đọc [CONTRIBUTING.md](../../CONTRIBUTING.md) và [AGENTS.md](../../AGENTS.md); commit cần DCO sign-off. Giấy phép: [GPL-3.0-or-later](../../LICENSE), với ghi nhận tại [NOTICE](../../NOTICE) và [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md). Nanodot không liên quan đến Meta hay nhãn hiệu Muse.

## Lịch sử và ghi nhận dự án gốc

Tin tức, trích dẫn, thẻ người bảo trì và đóng góp, cùng lời cảm ơn dưới đây là hồ sơ nanoMuse. Giữ nguyên ngày, tên và liên kết của dự án gốc; đây không phải bản phát hành hay người bảo trì Nanodot.

<details>
<summary>nanoMuse</summary>

## 🗞️ Tin mới

- `2026-10-09` 🚀 Phiên bản [1.0.0 Keel](https://github.com/nano-muse/nanoMuse/releases/tag/v1.0.0) đã ra mắt, cho điện thoại, máy tính và trình duyệt; cài đè lên bản trước mà vẫn giữ dữ liệu của bạn, và ghi chú phát hành liệt kê mọi thay đổi.
- `2026-10-08` 🤗 Bài báo của chúng tôi có mặt trên [Hugging Face Daily Papers](https://huggingface.co/papers/2610.08699), xếp thứ ba trong danh sách ngày 8 tháng 10; câu hỏi và góp ý xin gửi tại trang bài báo.
- `2026-10-07` 📄 Bài báo của chúng tôi đã có trên [arXiv](https://arxiv.org/abs/2610.08699): *nanoMuse: An Open-Source Personal Agent for Every Device You Own*, tác giả Guangyi Liu, Yong Liu và Jiangning Zhang (Đại học Chiết Giang); trích dẫn ở cuối trang này.
- `2026-09-25` 🎉 nanoMuse đã ra mắt trên GitHub theo GPL-3.0-or-later: một ứng dụng Android xây trên OpenMinis 1.13; relay, ứng dụng máy tính và ứng dụng iPhone lần lượt ra mắt trong những ngày sau đó.

Mọi phiên bản: [releases](https://github.com/nano-muse/nanoMuse/releases).

## Người bảo trì

<table>
  <tr>
    <td align="center"><a href="https://github.com/lgy0404"><img src="https://avatars.githubusercontent.com/u/63797388?v=4&s=80" width="80" height="80" alt="Guangyi Liu"><br><strong>Guangyi Liu</strong></a><br><a href="mailto:guangyiliu@zju.edu.cn"><img src="https://img.shields.io/badge/e--mail-guangyiliu%40zju.edu.cn-0a66e4" alt="guangyiliu@zju.edu.cn"></a></td>
  </tr>
</table>

## Người đóng góp từ cộng đồng

Tất cả những ai có thay đổi đã vào `main`, theo danh sách người đóng góp của GitHub.

<!-- contributors:start -->
<p>
<a href="https://github.com/wsvn53"><img src="https://avatars.githubusercontent.com/u/912204?v=4&s=48" width="48" height="48" alt="wsvn53"></a>
<a href="https://github.com/aliabbas6622"><img src="https://avatars.githubusercontent.com/u/193962983?v=4&s=48" width="48" height="48" alt="aliabbas6622"></a>
<a href="https://github.com/PovedaAqui"><img src="https://avatars.githubusercontent.com/u/9494679?v=4&s=48" width="48" height="48" alt="PovedaAqui"></a>
<a href="https://github.com/jiangkaiqi2005"><img src="https://avatars.githubusercontent.com/u/184961141?v=4&s=48" width="48" height="48" alt="jiangkaiqi2005"></a>
</p>
<!-- contributors:end -->

## ⭐️ Trích dẫn

Nếu nanoMuse hữu ích với bạn, hãy trích dẫn bài báo.

```bibtex
@misc{liu2026nanomuseopensourcepersonalagent,
      title={nanoMuse: An Open-Source Personal Agent for Every Device You Own}, 
      author={Guangyi Liu and Yong Liu and Jiangning Zhang},
      year={2026},
      eprint={2610.08699},
      archivePrefix={arXiv},
      primaryClass={cs.AI},
      url={https://arxiv.org/abs/2610.08699}, 
}
```

## Ghi nhận

nanoMuse được xây trên công sức của người khác; điều khoản ở [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).

- [OpenMinis](https://github.com/OpenMinis/OpenMinis): trợ lý chạy ngay trên thiết bị, là nền của ứng dụng điện thoại, với [proot](https://github.com/nano-muse/proot) và [Alpine Linux](https://alpinelinux.org/) làm hộp cát.
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): harness mà ứng dụng máy tính là một plugin.
- [UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop) (ByteDance): bộ điều khiển đôi tay trên máy tính được chuyển từ mã của họ, và các điểm đánh dấu trên sân khấu theo ScreenMarker của họ.
- [MobileGym](https://github.com/Purewhiter/mobilegym), [MemGUI-Bench](https://github.com/lgy0404/MemGUI-Bench), [PhoneHarness](https://github.com/PhoneHarness/PhoneHarness), [CopilotKit/OpenMuse](https://github.com/CopilotKit/OpenMuse), [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM), [ClawGUI](https://github.com/ZJU-REAL/ClawGUI): bộ điều khiển điện thoại, dấu vết và ý tưởng sản phẩm.

</details>

<p align="center"><img src="https://visitor-badge.laobi.icu/badge?page_id=Mola-maker.nanodot&amp;left_text=visitors" alt="Nanodot visitors"></p>
