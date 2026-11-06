//! The server thread's end, logged.

/// Says how the thread ended when nothing else did (a thread that left without an error or a
/// panic message), when dropped at its end.
pub(super) struct Ends;
impl Drop for Ends {
    fn drop(&mut self) {
        if std::thread::panicking() {
            log::error!("Horizon: the server thread ends by unwinding");
        } else {
            log::info!("Horizon: the server thread ends");
        }
        horizon_client::logging::flush();
    }
}
